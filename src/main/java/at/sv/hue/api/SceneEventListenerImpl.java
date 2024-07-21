package at.sv.hue.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.List;

@Slf4j
public final class SceneEventListenerImpl implements SceneEventListener {

    private final HueApi hueApi;
    private final Cache<String, String> recentlyAffectedIds;

    public SceneEventListenerImpl(HueApi hueApi, Ticker ticker, int ignoreWindowInSeconds) {
        this.hueApi = hueApi;
        recentlyAffectedIds = Caffeine.newBuilder()
                                      .ticker(ticker)
                                      .expireAfterWrite(Duration.ofSeconds(ignoreWindowInSeconds))
                                      .build();
    }

    @Override
    public void onSceneActivated(String id) {
        MDC.put("context", "scene-on-event " + id);
        List<String> sceneLights = hueApi.getAffectedIdsByScene(id);
        sceneLights.forEach(lightId -> recentlyAffectedIds.put(lightId, lightId));
        sceneLights.stream()
                   .flatMap(light -> hueApi.getAssignedGroups(light).stream())
                   .distinct()
                   .forEach(groupId -> recentlyAffectedIds.put(groupId, groupId));
    }

    @Override
    public boolean wasRecentlyAffectedByAScene(String id) {
        return recentlyAffectedIds.getIfPresent(id) != null;
    }
}
