package com.keyloop.scheduler.availability;

import java.util.UUID;

public record AvailabilityRequest(UUID customerId, UUID vehicleId, UUID dealershipId,
                                  UUID serviceTypeId, String startsAt) { }
