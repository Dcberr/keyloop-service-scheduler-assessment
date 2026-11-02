package com.keyloop.scheduler.catalog;

import com.keyloop.scheduler.shared.Page;
import java.util.UUID;

/** Read-only discovery facade, preserving one boundary for future HTTP controllers. */
@org.springframework.stereotype.Service
public class CatalogService {
    private final CatalogRepository repository;

    public CatalogService(CatalogRepository repository) { this.repository = repository; }

    public Page<Dealership> dealerships(int page, int size) { return repository.dealerships(page, size); }
    public Page<Service> services(UUID dealershipId, int page, int size) { return repository.services(dealershipId, page, size); }
    public Page<Customer> customers(int page, int size) { return repository.customers(page, size); }
    public Page<Vehicle> vehicles(UUID customerId, int page, int size) { return repository.vehicles(customerId, page, size); }

    public record Dealership(UUID id, String name, String timeZone) { }
    public record Service(UUID id, String name, int durationMinutes) { }
    public record Customer(UUID id, String displayName) { }
    public record Vehicle(UUID id, UUID customerId, String vin, String make, String model) { }
}
