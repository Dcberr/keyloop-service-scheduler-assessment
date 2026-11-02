package com.keyloop.scheduler.api;

import com.keyloop.scheduler.appointment.Appointment;
import com.keyloop.scheduler.availability.AvailabilityRequest;
import com.keyloop.scheduler.availability.AvailabilityResult;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.shared.Page;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public final class ApiDtos {
    private ApiDtos() { }
    public record BookingBody(@NotBlank String customerId,@NotBlank String vehicleId,@NotBlank String dealershipId,
                              @NotBlank String serviceTypeId,@NotBlank String startsAt) {
        AvailabilityRequest request() { return new AvailabilityRequest(ApiIds.required(customerId),ApiIds.required(vehicleId),ApiIds.required(dealershipId),ApiIds.required(serviceTypeId),startsAt); }
    }
    public record AppointmentResponse(UUID id,UUID customerId,UUID vehicleId,UUID dealershipId,UUID serviceTypeId,
                                      UUID technicianId,UUID serviceBayId,String startsAt,String endsAt,int durationMinutes,
                                      String dealershipTimeZone,String status,String createdAt) {
        static AppointmentResponse from(Appointment a) { return new AppointmentResponse(a.id(),a.customerId(),a.vehicleId(),a.dealershipId(),a.serviceTypeId(),a.technicianId(),a.serviceBayId(),a.startsAt().toString(),a.endsAt().toString(),a.durationMinutes(),a.dealershipTimeZone(),a.status(),a.createdAt().toString()); }
    }
    public record AvailabilityResponse(boolean available,String startsAt,String endsAt,int durationMinutes,
                                       String dealershipTimeZone,String checkedAt,String reason) {
        static AvailabilityResponse from(AvailabilityResult a) { return new AvailabilityResponse(a.available(),a.startsAt().toString(),a.endsAt().toString(),a.durationMinutes(),a.dealershipTimeZone(),a.checkedAt().toString(),a.reason()==null?null:a.reason().name()); }
    }
    public record DealershipResponse(UUID id,String name,String timeZone) { static DealershipResponse from(CatalogService.Dealership d){return new DealershipResponse(d.id(),d.name(),d.timeZone());} }
    public record ServiceResponse(UUID id,String name,int durationMinutes) { static ServiceResponse from(CatalogService.Service s){return new ServiceResponse(s.id(),s.name(),s.durationMinutes());} }
    public record CustomerResponse(UUID id,String displayName) { static CustomerResponse from(CatalogService.Customer c){return new CustomerResponse(c.id(),c.displayName());} }
    public record VehicleResponse(UUID id,UUID customerId,String vin,String make,String model) { static VehicleResponse from(CatalogService.Vehicle v){return new VehicleResponse(v.id(),v.customerId(),v.vin(),v.make(),v.model());} }
    public record PageResponse<T>(List<T> items,int page,int size,long totalElements) {
        static <T,R> PageResponse<R> from(Page<T> page,Function<T,R> mapper) {return new PageResponse<>(page.items().stream().map(mapper).toList(),page.page(),page.size(),page.totalElements());}
    }
}
