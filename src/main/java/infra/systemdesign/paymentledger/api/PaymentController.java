package infra.systemdesign.paymentledger.api;

import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.application.PaymentIntakeService;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
class PaymentController {

    private final PaymentIntakeService paymentIntakeService;

    PaymentController(PaymentIntakeService paymentIntakeService) {
        this.paymentIntakeService = paymentIntakeService;
    }

    @PostMapping
    PaymentResponse createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PaymentRequest request
    ) {
        return paymentIntakeService.process(idempotencyKey, request);
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    ResponseEntity<ErrorResponse> duplicateIdempotencyKey(DuplicateIdempotencyKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_PAYMENT_REQUEST", exception.getMessage()));
    }

    record ErrorResponse(String code, String message) {}
}
