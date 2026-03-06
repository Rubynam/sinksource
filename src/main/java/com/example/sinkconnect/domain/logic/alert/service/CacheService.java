package com.example.sinkconnect.domain.logic.alert.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * CacheService - Redis-based cache for previous prices
 *
 * Responsibilities:
 * 1. Store previous prices for cross detection (CROSS_ABOVE/CROSS_BELOW)
 * 2. Use distributed Redis cache (replaces HashMap for multi-instance support)
 * 3. Memory-efficient key pattern: PREVIOUS_PRICE:<SOURCE>:<SYMBOL>
 * 4. TTL-based expiration for old prices (1 hour)
 * 5. Distributed locking support (future enhancement)
 *
 * Memory Optimization:
 * - Redis optimizes small hashes using ziplist/listpack
 * - Uses significantly less RAM than individual top-level keys
 * - Eliminates per-key metadata overhead
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Key prefix for previous prices
    private static final String PREVIOUS_PRICE_PREFIX = "PREVIOUS_PRICE:";

    // TTL for previous prices (1 hour - prevents stale data)
    private static final Duration PRICE_TTL = Duration.ofHours(1);

    /**
     * Get previous price for a symbol with fault tolerance
     *
     * @param source Exchange source (e.g., "BINANCE")
     * @param symbol Trading symbol (e.g., "BTCUSDT")
     * @return Previous price or null if not found
     */
    public BigDecimal getPreviousPrice(String source, String symbol) {
        String key = buildPriceKey(source, symbol);

        try {
            Object value = redisTemplate.opsForValue().get(key);

            if (value == null) {
                log.debug("No previous price found for {}-{}", source, symbol);
                return null;
            }

            // Handle different number types from Redis
            if (value instanceof BigDecimal) {
                return (BigDecimal) value;
            } else if (value instanceof Number) {
                return new BigDecimal(value.toString());
            } else {
                log.warn("Unexpected type for previous price: {}", value.getClass());
                return null;
            }

        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            // Redis connection failure - this is expected during Redis downtime
            // Log as WARN instead of ERROR to reduce noise in logs
            log.warn("Redis connection failure when getting previous price for {}-{}: {}. " +
                    "Alert processing will continue without cross detection.",
                    source, symbol, e.getMessage());
            return null;

        } catch (org.springframework.data.redis.RedisSystemException e) {
            // Redis system error (timeout, etc.)
            log.warn("Redis system error when getting previous price for {}-{}: {}. " +
                    "Alert processing will continue without cross detection.",
                    source, symbol, e.getMessage());
            return null;

        } catch (Exception e) {
            // Unexpected error - log as ERROR for investigation
            log.error("Unexpected error getting previous price for {}-{}: {}. " +
                    "Alert processing will continue without cross detection.",
                    source, symbol, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Set previous price for a symbol with TTL
     *
     * @param source Exchange source
     * @param symbol Trading symbol
     * @param price Price to store
     */
    public void setPreviousPrice(String source, String symbol, BigDecimal price) {
        if (price == null) {
            log.warn("Attempted to set null price for {}-{}, skipping", source, symbol);
            return;
        }

        String key = buildPriceKey(source, symbol);

        try {
            redisTemplate.opsForValue().set(key, price, PRICE_TTL);

            log.debug("Stored previous price for {}-{}: {}", source, symbol, price);

        } catch (Exception e) {
            log.error("Failed to set previous price for {}-{}: {}",
                    source, symbol, e.getMessage(), e);
        }
    }

    /**
     * Update previous price atomically (get-and-set pattern)
     *
     * @param source Exchange source
     * @param symbol Trading symbol
     * @param newPrice New price to store
     * @return Previous price (before update) or null
     */
    public BigDecimal updatePreviousPrice(String source, String symbol, BigDecimal newPrice) {
        if (newPrice == null) {
            return null;
        }

        String key = buildPriceKey(source, symbol);

        try {
            // Get old value and set new value atomically
            Object oldValue = redisTemplate.opsForValue().getAndSet(key, newPrice);

            // Set TTL on the key
            redisTemplate.expire(key, PRICE_TTL);

            if (oldValue == null) {
                log.debug("First price set for {}-{}: {}", source, symbol, newPrice);
                return null;
            }

            // Convert old value to BigDecimal
            if (oldValue instanceof BigDecimal) {
                return (BigDecimal) oldValue;
            } else if (oldValue instanceof Number) {
                return new BigDecimal(oldValue.toString());
            } else {
                log.warn("Unexpected old value type: {}", oldValue.getClass());
                return null;
            }

        } catch (Exception e) {
            log.error("Failed to update previous price for {}-{}: {}",
                    source, symbol, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Delete previous price for a symbol
     *
     * @param source Exchange source
     * @param symbol Trading symbol
     */
    public void deletePreviousPrice(String source, String symbol) {
        String key = buildPriceKey(source, symbol);

        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Deleted previous price for {}-{}", source, symbol);
            }
        } catch (Exception e) {
            log.error("Failed to delete previous price for {}-{}: {}",
                    source, symbol, e.getMessage(), e);
        }
    }

    /**
     * Acquire distributed lock (for future enhancements)
     *
     * @param lockKey Lock identifier
     * @param timeout Lock timeout
     * @return true if lock acquired
     */
    public boolean acquireLock(String lockKey, Duration timeout) {
        String key = "LOCK:" + lockKey;

        try {
            // SET NX (set if not exists) with expiration
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, "locked", timeout);

            return Boolean.TRUE.equals(acquired);

        } catch (Exception e) {
            log.error("Failed to acquire lock {}: {}", lockKey, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Release distributed lock
     *
     * @param lockKey Lock identifier
     */
    public void releaseLock(String lockKey) {
        String key = "LOCK:" + lockKey;

        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("Failed to release lock {}: {}", lockKey, e.getMessage(), e);
        }
    }

    /**
     * Check Redis connection health
     *
     * @return true if Redis is reachable
     */
    public boolean isHealthy() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return true;
        } catch (Exception e) {
            log.error("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get cache statistics (for monitoring)
     *
     * @return Number of previous price keys stored
     */
    public long getPreviousPriceCount() {
        try {
            return redisTemplate.keys(PREVIOUS_PRICE_PREFIX + "*").size();
        } catch (Exception e) {
            log.error("Failed to get previous price count: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Build Redis key for previous price
     * Pattern: PREVIOUS_PRICE:<SOURCE>:<SYMBOL>
     *
     * @param source Exchange source
     * @param symbol Trading symbol
     * @return Redis key
     */
    private String buildPriceKey(String source, String symbol) {
        return PREVIOUS_PRICE_PREFIX + source + ":" + symbol;
    }
}