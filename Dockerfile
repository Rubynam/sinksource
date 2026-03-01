# Standalone Redis Dockerfile for Sink Connect Alert System
# This provides a lightweight, production-ready Redis instance

FROM redis:7.2-alpine

# Set working directory
WORKDIR /data

# Copy custom Redis configuration (optional)
# COPY redis.conf /usr/local/etc/redis/redis.conf

# Expose Redis port
EXPOSE 6379

# Health check
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD redis-cli ping || exit 1

# Run Redis with custom configuration
# Memory optimization for previous price storage:
# - maxmemory: Limit memory usage
# - maxmemory-policy: Eviction policy (allkeys-lru for LRU eviction)
# - save: Persistence (60 seconds if at least 1 key changed)
CMD ["redis-server", \
     "--maxmemory", "256mb", \
     "--maxmemory-policy", "allkeys-lru", \
     "--save", "60", "1", \
     "--loglevel", "notice"]