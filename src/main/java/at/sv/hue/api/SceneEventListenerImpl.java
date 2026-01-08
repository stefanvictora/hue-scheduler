package at.sv.hue.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

@Slf4j
public final class SceneEventListenerImpl implements SceneEventListener {

    private final HueApi hueApi;
    private final Predicate<String> matchesSyncedSceneName;
    private final LightEventListener lightEventListener;
    private final Cache<String, String> recentlyAffectedIds;
    private final Cache<String, String> recentlyAffectedSyncedIds;

    public SceneEventListenerImpl(HueApi hueApi, Ticker ticker, int ignoreWindowInSeconds,
                                  Predicate<String> matchesSyncedSceneName,
                                  LightEventListener lightEventListener) {
        this.hueApi = hueApi;
        this.matchesSyncedSceneName = matchesSyncedSceneName;
        this.lightEventListener = lightEventListener;
        recentlyAffectedIds = Caffeine.newBuilder()
                                      .ticker(ticker)
                                      .expireAfterWrite(Duration.ofSeconds(ignoreWindowInSeconds))
                                      .build();
        recentlyAffectedSyncedIds = Caffeine.newBuilder()
                                            .ticker(ticker)
                                            .expireAfterWrite(Duration.ofSeconds(ignoreWindowInSeconds))
                                            .build();
    }

    @Override
    public void onSceneActivated(String id) {
        MDC.put("context", "scene-on-event " + id);
        String sceneName = hueApi.getSceneName(id);
        MDC.put("context", "scene-on-event " + sceneName);
        Set<AffectedId> affectedIdsByScene = getAffectedIdsForScene(id);
        if (isSyncedScene(sceneName)) {
            log.info("Synced scene activated. Re-engage scheduler.");
            affectedIdsByScene.forEach(lightOrGroupId -> recentlyAffectedSyncedIds.put(lightOrGroupId.id(), lightOrGroupId.id()));
            List<String> alreadyOnIds = affectedIdsByScene.stream()
                                                          .filter(AffectedId::alreadyOn)
                                                          .map(AffectedId::id)
                                                          .toList();
            alreadyOnIds.forEach(lightEventListener::onLightOn); // only re-engage scheduler for lights that are already on; others will be handled when they are turned on
            MDC.remove("context");
            return;
        }
        affectedIdsByScene.forEach(lightOrGroupId -> recentlyAffectedIds.put(lightOrGroupId.id(), lightOrGroupId.id()));
        MDC.remove("context");
    }

    private Set<AffectedId> getAffectedIdsForScene(String sceneId) {
        Set<AffectedId> affectedIds = new HashSet<>(hueApi.getAffectedIdsByScene(sceneId));
        affectedIds.addAll(fetchAdditionalGroupsForLights(affectedIds));
        return affectedIds;
    }

    private List<AffectedId> fetchAdditionalGroupsForLights(Set<AffectedId> ids) {
        return ids.stream()
                  .flatMap(lightId -> hueApi.getAssignedGroups(lightId.id()).stream())
                  .distinct()
                  .map(groupId -> new AffectedId(groupId, !hueApi.isGroupOff(groupId)))
                  .toList();
    }

    private boolean isSyncedScene(String sceneName) {
        return matchesSyncedSceneName.test(sceneName);
    }

    @Override
    public boolean wasRecentlyAffectedByNormalScene(String id) {
        return recentlyAffectedIds.getIfPresent(id) != null;
    }

    @Override
    public boolean wasRecentlyAffectedBySyncedScene(String id) {
        return recentlyAffectedSyncedIds.getIfPresent(id) != null;
    }
}
