package com.backyard.playground.service.driver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.backyard.playground.data.model.driver.DriverPositionDTO;

@Repository
@Profile("!home")
public class DriverRedisRepository {

    private static final String S2_INDEX_KEY = "drivers:s2index";

    private final StringRedisTemplate redis;

    public DriverRedisRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @SuppressWarnings("resource")
    public void updateAll(Driver[] drivers, S2Service s2Service) {
        redis.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = new DefaultStringRedisConnection(connection);
            for (Driver d : drivers) {
                String key = "driver:" + d.getId();
                conn.hSet(key, "lat", String.valueOf(d.getLat()));
                conn.hSet(key, "lng", String.valueOf(d.getLng()));
                conn.zAdd(S2_INDEX_KEY, (double) s2Service.cellId(d.getLat(), d.getLng()), d.getId().toString());
            }
            return null;
        });
    }

    @SuppressWarnings({ "unchecked", "resource" })
    public List<DriverPositionDTO> getPositions(Collection<UUID> ids) {
        if (ids.isEmpty())
            return List.of();
        List<UUID> idList = new ArrayList<>(ids);
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = new DefaultStringRedisConnection(connection);
            for (UUID id : idList) {
                conn.hMGet("driver:" + id, "lat", "lng");
            }
            return null;
        });
        List<DriverPositionDTO> positions = new ArrayList<>(idList.size());
        for (int i = 0; i < idList.size(); i++) {
            List<String> fields = (List<String>) results.get(i);
            if (fields != null && fields.get(0) != null && fields.get(1) != null) {
                positions.add(new DriverPositionDTO(
                        idList.get(i),
                        Double.parseDouble(fields.get(0)),
                        Double.parseDouble(fields.get(1))));
            }
        }
        return positions;
    }

    /**
     * Rebuilds the full driver array from Redis on leader takeover. Step 1: ZRANGE
     * to get all UUIDs; step 2: pipeline HMGET for positions. Velocities are
     * randomized since they are not persisted.
     */
    public Driver[] loadAll(Random rng) {
        Set<String> members = redis.opsForZSet().range(S2_INDEX_KEY, 0, -1);
        if (members == null || members.isEmpty())
            return new Driver[0];
        List<UUID> ids = members.stream().map(UUID::fromString).toList();
        List<DriverPositionDTO> positions = getPositions(ids);
        Driver[] drivers = new Driver[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            DriverPositionDTO p = positions.get(i);
            drivers[i] = new Driver(p.getId(), p.getLat(), p.getLng(), rng);
        }
        return drivers;
    }

    public Set<UUID> queryViewport(List<long[]> ranges) {
        Set<UUID> result = new HashSet<>();
        for (long[] range : ranges) {
            Set<String> members = redis.opsForZSet().rangeByScore(S2_INDEX_KEY, range[0], range[1]);
            if (members != null) {
                for (String m : members) {
                    result.add(UUID.fromString(m));
                }
            }
        }
        return result;
    }
}
