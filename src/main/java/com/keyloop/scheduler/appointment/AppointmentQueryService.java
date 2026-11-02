package com.keyloop.scheduler.appointment;

import com.keyloop.scheduler.shared.Page;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AppointmentQueryService {
    private final AppointmentQueryRepository repository;
    public AppointmentQueryService(AppointmentQueryRepository repository) { this.repository=repository; }
    public Appointment get(UUID id) { return repository.get(id); }
    public Page<Appointment> history(UUID customerId,UUID vehicleId,int page,int size) { return repository.history(customerId,vehicleId,page,size); }
}
