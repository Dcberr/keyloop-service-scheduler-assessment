package com.keyloop.scheduler.api;

import com.keyloop.scheduler.appointment.AppointmentQueryService;
import com.keyloop.scheduler.appointment.BookingService;
import com.keyloop.scheduler.availability.AvailabilityRequest;
import com.keyloop.scheduler.availability.AvailabilityService;
import com.keyloop.scheduler.catalog.CatalogService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.keyloop.scheduler.api.ApiDtos.*;

@RestController
@RequestMapping("/api/v1")
public class SchedulerController {
    private final CatalogService catalog;
    private final AvailabilityService availability;
    private final BookingService booking;
    private final AppointmentQueryService appointments;
    public SchedulerController(CatalogService catalog,AvailabilityService availability,BookingService booking,AppointmentQueryService appointments) {
        this.catalog=catalog;this.availability=availability;this.booking=booking;this.appointments=appointments;
    }
    @GetMapping("/dealerships") public PageResponse<DealershipResponse> dealerships(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return PageResponse.from(catalog.dealerships(page,size),DealershipResponse::from);}
    @GetMapping("/dealerships/{id}/services") public PageResponse<ServiceResponse> services(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return PageResponse.from(catalog.services(ApiIds.required(id),page,size),ServiceResponse::from);}
    @GetMapping("/customers") public PageResponse<CustomerResponse> customers(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return PageResponse.from(catalog.customers(page,size),CustomerResponse::from);}
    @GetMapping("/customers/{id}/vehicles") public PageResponse<VehicleResponse> vehicles(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return PageResponse.from(catalog.vehicles(ApiIds.required(id),page,size),VehicleResponse::from);}
    @GetMapping("/availability") public AvailabilityResponse availability(@RequestParam String customerId,@RequestParam String vehicleId,@RequestParam String dealershipId,@RequestParam String serviceTypeId,@RequestParam String startsAt) {
        return AvailabilityResponse.from(availability.check(new AvailabilityRequest(ApiIds.required(customerId),ApiIds.required(vehicleId),ApiIds.required(dealershipId),ApiIds.required(serviceTypeId),startsAt)));
    }
    @PostMapping(value="/appointments",consumes="application/json") public ResponseEntity<AppointmentResponse> book(@Valid @RequestBody BookingBody body) {
        var result=booking.book(body.request());return ResponseEntity.created(URI.create("/api/v1/appointments/"+result.id())).body(AppointmentResponse.from(result));
    }
    @GetMapping("/appointments/{id}") public AppointmentResponse appointment(@PathVariable String id) {return AppointmentResponse.from(appointments.get(ApiIds.required(id)));}
    @GetMapping("/appointments") public PageResponse<AppointmentResponse> history(@RequestParam String customerId,@RequestParam(required=false) String vehicleId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return PageResponse.from(appointments.history(ApiIds.required(customerId),vehicleId==null?null:ApiIds.required(vehicleId),page,size),AppointmentResponse::from);
    }
}
