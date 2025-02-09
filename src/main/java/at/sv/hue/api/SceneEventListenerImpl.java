package at.sv.hue.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
public final class SceneEventListenerImpl implements SceneEventListener {

    private final HueApi hueApi;
    private final Predicate<String> matchesSyncedSceneName;
    private final LightEventListener lightEventListener;
    private final Cache<String, String> recentlyAffectedIds;

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
    }

    @Override
    public void onSceneActivated(String id) {
        MDC.put("context", "scene-on-event " + id);
        String sceneName = hueApi.getSceneName(id);
        MDC.put("context", "scene-on-event " + sceneName);
        List<String> affectedIdsByScene = getAffectedIdsByScene(id);
        if (matchesSyncedSceneName.test(sceneName)) {
            log.info("Synced scene activated. Re-engage scheduler.");
            affectedIdsByScene.forEach(lightEventListener::onLightOn);
            return;
        }
        affectedIdsByScene.forEach(lightOrGroupId -> recentlyAffectedIds.put(lightOrGroupId, lightOrGroupId));
    }

    private List<String> getAffectedIdsByScene(String sceneId) {
        List<String> affectedIdsByScene = new ArrayList<>(hueApi.getAffectedIdsByScene(sceneId));
        affectedIdsByScene.addAll(getAdditionalAffectedIds(affectedIdsByScene));
        return affectedIdsByScene;
    }

    private List<String> getAdditionalAffectedIds(List<String> ids) {
        return ids.stream()
                  .flatMap(light -> hueApi.getAssignedGroups(light).stream())
                  .distinct()
                  .collect(Collectors.toList());
    }

    @Override
    public boolean wasRecentlyAffectedByAScene(String id) {
        return recentlyAffectedIds.getIfPresent(id) != null;
    }
}
