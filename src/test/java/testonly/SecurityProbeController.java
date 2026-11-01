package testonly;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Test-only controller used to exercise SecurityConfig URL rules without touching real services. */
@RestController
public class SecurityProbeController {

    @GetMapping("/console-api/v1/probe")
    public Map<String, Boolean> readConsole() {
        return Map.of("ok", true);
    }

    @PutMapping("/console-api/v1/probe")
    public Map<String, Boolean> writeConsole() {
        return Map.of("ok", true);
    }

    @GetMapping("/console-api/v1/audit")
    public Map<String, Boolean> audit() {
        return Map.of("ok", true);
    }

    @GetMapping("/admin/probe")
    public Map<String, Boolean> readAdmin() {
        return Map.of("ok", true);
    }

    @PutMapping("/admin/probe")
    public Map<String, Boolean> writeAdmin() {
        return Map.of("ok", true);
    }

    @GetMapping("/actuator/probe")
    public Map<String, Boolean> actuator() {
        return Map.of("ok", true);
    }

    @PostMapping("/bid")
    public Map<String, Boolean> bid() {
        return Map.of("ok", true);
    }
}
