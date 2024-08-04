package at.sv.hue.api;

/**
 * Exception to signal a backend error of the API (5xx, 429). Or when its response could not be parsed.
 * ApiFailures cause the scheduler to retry the call.
 */
public class ApiFailure extends RuntimeException {
    public ApiFailure(String message) {
        super(message);
    }
}
