package com.keyloop.scheduler.appointment;

import com.keyloop.scheduler.shared.BusinessException;
import java.sql.SQLException;
import java.util.Map;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.stereotype.Component;

/** Interprets database failures only after TransactionTemplate has rolled back. */
@Component
public class BookingFailureMapper {
    private static final Map<String, String> OVERLAP_CODES = Map.of(
            "appointments_no_bay_overlap", "BOOKING_CONFLICT",
            "appointments_no_technician_overlap", "BOOKING_CONFLICT",
            "appointments_no_vehicle_overlap", "VEHICLE_UNAVAILABLE");

    public BusinessException map(RuntimeException failure) {
        if (failure instanceof BusinessException business) return business;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                for (SQLException current = sql; current != null; current = current.getNextException()) {
                    String state = current.getSQLState();
                    if ("23P01".equals(state)) {
                        // Read structured diagnostics, never a localized human message or DETAIL.
                        if (current instanceof PSQLException postgres && postgres.getServerErrorMessage() != null) {
                            String constraint = postgres.getServerErrorMessage().getConstraint();
                            String code = constraint == null ? null : OVERLAP_CODES.get(constraint);
                            if (code != null) {
                                return new BusinessException(409, code, "The requested resources were booked concurrently");
                            }
                        }
                    }
                    if (state != null && (state.startsWith("08") || state.equals("55P03")
                            || state.equals("57014") || state.equals("40P01") || state.equals("40001"))) {
                        return unavailable();
                    }
                }
            }
            if (cause instanceof TransactionTimedOutException || cause instanceof CannotCreateTransactionException
                    || cause instanceof DataAccessResourceFailureException) return unavailable();
        }
        return new BusinessException(500, "INTERNAL_ERROR", "The appointment could not be created");
    }

    private static BusinessException unavailable() {
        return new BusinessException(503, "TEMPORARILY_UNAVAILABLE", "Booking is temporarily unavailable; check appointment history before retrying");
    }
}
