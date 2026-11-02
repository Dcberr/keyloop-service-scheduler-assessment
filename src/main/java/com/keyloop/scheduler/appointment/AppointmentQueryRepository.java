package com.keyloop.scheduler.appointment;

import com.keyloop.scheduler.shared.BusinessException;
import com.keyloop.scheduler.shared.Page;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AppointmentQueryRepository {
    private static final String SELECT = "SELECT a.*,d.time_zone FROM appointments a JOIN dealerships d ON d.id=a.dealership_id ";
    private final JdbcClient jdbc;
    public AppointmentQueryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Appointment get(UUID id) {
        return jdbc.sql(SELECT + "WHERE a.id=:id").param("id",id).query((rs,row)->map(rs))
                .optional().orElseThrow(()->new BusinessException(404,"RESOURCE_NOT_FOUND","Appointment does not exist"));
    }

    public Page<Appointment> history(UUID customerId, UUID vehicleId, int page, int size) {
        long offset=Page.offset(page,size);
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM customers WHERE id=:id)").param("id",customerId).query(Boolean.class).single()) {
            throw new BusinessException(404,"RESOURCE_NOT_FOUND","Customer does not exist");
        }
        if(vehicleId!=null) {
            UUID owner=jdbc.sql("SELECT customer_id FROM vehicles WHERE id=:id").param("id",vehicleId).query(UUID.class).optional()
                    .orElseThrow(()->new BusinessException(404,"RESOURCE_NOT_FOUND","Vehicle does not exist"));
            if(!owner.equals(customerId)) throw new BusinessException(422,"VEHICLE_CUSTOMER_MISMATCH","The vehicle does not belong to the customer");
        }
        String where="WHERE a.customer_id=:customer"+(vehicleId==null?"":" AND a.vehicle_id=:vehicle");
        var count=jdbc.sql("SELECT count(*) FROM appointments a "+where).param("customer",customerId);
        var rows=jdbc.sql(SELECT+where+" ORDER BY a.starts_at,a.id LIMIT :size OFFSET :offset")
                .param("customer",customerId).param("size",size).param("offset",offset);
        if(vehicleId!=null) { count.param("vehicle",vehicleId); rows.param("vehicle",vehicleId); }
        return new Page<>(rows.query((rs,row)->map(rs)).list(),page,size,count.query(Long.class).single());
    }

    private Appointment map(ResultSet rs) throws SQLException {
        return new Appointment(rs.getObject("id",UUID.class),rs.getObject("customer_id",UUID.class),
                rs.getObject("vehicle_id",UUID.class),rs.getObject("dealership_id",UUID.class),
                rs.getObject("service_type_id",UUID.class),rs.getObject("technician_id",UUID.class),
                rs.getObject("service_bay_id",UUID.class),rs.getObject("starts_at",OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at",OffsetDateTime.class).toInstant(),rs.getInt("duration_minutes"),
                rs.getString("time_zone"),rs.getString("status"),rs.getObject("created_at",OffsetDateTime.class).toInstant());
    }
}
