package com.example.sinkconnect.infrastructure.config;

import akka.persistence.journal.EventAdapter;
import akka.persistence.journal.EventSeq;

/**
 * AlertEventAdapter - Optional event adapter for tagging/transformation
 *
 * This is a placeholder for future enhancements like:
 * - Event tagging for read-side projections
 * - Event schema migration
 * - Event enrichment
 *
 * Currently a pass-through adapter (no transformation)
 */
public class AlertEventAdapter implements EventAdapter {

    @Override
    public String manifest(Object event) {
        // Return event class name for serialization hint
        return event.getClass().getSimpleName();
    }

    @Override
    public Object toJournal(Object event) {
        // Pass through (no transformation)
        return event;
    }

    @Override
    public EventSeq fromJournal(Object event, String manifest) {
        // Pass through (no transformation)
        return EventSeq.single(event);
    }
}