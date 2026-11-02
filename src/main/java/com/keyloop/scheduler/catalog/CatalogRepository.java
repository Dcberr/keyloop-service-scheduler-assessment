package com.keyloop.scheduler.catalog;

import com.keyloop.scheduler.shared.BusinessException;
import com.keyloop.scheduler.shared.Page;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.keyloop.scheduler.catalog.CatalogService.Dealership;
import com.keyloop.scheduler.catalog.CatalogService.Service;
import com.keyloop.scheduler.catalog.CatalogService.Customer;
import com.keyloop.scheduler.catalog.CatalogService.Vehicle;

/** Read-only demo discovery. Reference data is immutable during baseline operation. */
@Repository
public class CatalogRepository {
    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Page<Dealership> dealerships(int page, int size) {
        long offset = Page.offset(page, size);
        long total = jdbc.sql("SELECT count(*) FROM dealerships WHERE active").query(Long.class).single();
        var items = jdbc.sql("SELECT id,name,time_zone FROM dealerships WHERE active ORDER BY id LIMIT :size OFFSET :offset")
                .param("size", size).param("offset", offset)
                .query((rs, row) -> new Dealership(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("time_zone"))).list();
        return new Page<>(items, page, size, total);
    }

    public Page<Service> services(UUID dealershipId, int page, int size) {
        long offset = Page.offset(page, size);
        requireId(dealershipId);
        boolean active = jdbc.sql("SELECT active FROM dealerships WHERE id=:id").param("id", dealershipId)
                .query(Boolean.class).optional().orElseThrow(() -> missing("Dealership"));
        if (!active) throw new BusinessException(422, "SERVICE_NOT_BOOKABLE", "The dealership is inactive");
        long total = jdbc.sql("SELECT count(*) FROM service_types s JOIN dealership_services d ON d.service_type_id=s.id WHERE d.dealership_id=:id AND s.active")
                .param("id", dealershipId).query(Long.class).single();
        var items = jdbc.sql("""
                SELECT s.id,s.name,s.duration_minutes FROM service_types s
                JOIN dealership_services d ON d.service_type_id=s.id
                WHERE d.dealership_id=:id AND s.active ORDER BY s.id LIMIT :size OFFSET :offset
                """).param("id", dealershipId).param("size", size).param("offset", offset)
                .query((rs, row) -> new Service(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("duration_minutes"))).list();
        return new Page<>(items, page, size, total);
    }

    public Page<Customer> customers(int page, int size) {
        long offset = Page.offset(page, size);
        long total = jdbc.sql("SELECT count(*) FROM customers").query(Long.class).single();
        var items = jdbc.sql("SELECT id,display_name FROM customers ORDER BY id LIMIT :size OFFSET :offset")
                .param("size", size).param("offset", offset)
                .query((rs, row) -> new Customer(rs.getObject("id", UUID.class), rs.getString("display_name"))).list();
        return new Page<>(items, page, size, total);
    }

    public Page<Vehicle> vehicles(UUID customerId, int page, int size) {
        long offset = Page.offset(page, size);
        requireId(customerId);
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM customers WHERE id=:id)").param("id", customerId).query(Boolean.class).single()) {
            throw missing("Customer");
        }
        long total = jdbc.sql("SELECT count(*) FROM vehicles WHERE customer_id=:id").param("id", customerId).query(Long.class).single();
        var items = jdbc.sql("SELECT id,customer_id,vin,make,model FROM vehicles WHERE customer_id=:id ORDER BY id LIMIT :size OFFSET :offset")
                .param("id", customerId).param("size", size).param("offset", offset)
                .query((rs, row) -> new Vehicle(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                        rs.getString("vin"), rs.getString("make"), rs.getString("model"))).list();
        return new Page<>(items, page, size, total);
    }

    private static BusinessException missing(String entity) {
        return new BusinessException(404, "RESOURCE_NOT_FOUND", entity + " does not exist");
    }
    private static void requireId(UUID id) {
        if (id == null) throw new BusinessException(400, "INVALID_REQUEST", "Parent ID is required");
    }

}
