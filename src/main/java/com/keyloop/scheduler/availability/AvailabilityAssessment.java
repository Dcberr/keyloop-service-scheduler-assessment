package com.keyloop.scheduler.availability;

import java.util.UUID;

/** Internal candidates for a future booking flow; never an external allocation promise. */
public record AvailabilityAssessment(AvailabilityResult result, UUID serviceBayId, UUID technicianId) { }
