package infra.systemdesign.paymentledger.application.port;

public class ReservationOwnershipLostException extends RuntimeException {

    public ReservationOwnershipLostException(String message) {
        super(message);
    }
}
