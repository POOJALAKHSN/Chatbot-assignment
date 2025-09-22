package com.poojalakshmi.chatbot_assignment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Single-file demo Chatbot platform:
 * - In-memory users, tokens, projects, prompts
 * - Register / login -> returns token
 * - Project CRUD (create/list) scoped to user
 * - Add prompts to project
 * - Chat endpoint that simulates an LLM reply using prompts + message
 *
 * How to run: mvn spring-boot:run
 */
@SpringBootApplication
public class AppSingleFull {
    public static void main(String[] args) {
        SpringApplication.run(AppSingleFull.class, args);
    }

    /* -------------------------
       Simple in-memory stores
       ------------------------- */
    static record User(Long id, String email, String passwordHash, String name) {}
    static record Project(Long id, Long ownerId, String name, List<String> prompts) {}
    static record AuthToken(String token, Long userId, Instant createdAt) {}

    static final Map<Long, User> users = new ConcurrentHashMap<>();
    static final Map<Long, Project> projects = new ConcurrentHashMap<>();
    static final Map<String, AuthToken> tokens = new ConcurrentHashMap<>();
    static final Map<String, Long> emailToUserId = new ConcurrentHashMap<>();

    static final Random rnd = new Random();
    static volatile long userSeq = 1;
    static volatile long projectSeq = 1;

    /* -------------------------
       Utility helpers
       ------------------------- */
    static String simpleHash(String s) {
        // Not secure cryptography — ok for demo only
        return Integer.toHexString(Objects.hash(s));
    }

    static String generateToken() {
        return UUID.randomUUID().toString();
    }

    static Optional<Long> userIdFromAuthHeader(String authHeader) {
        if (authHeader == null) return Optional.empty();
        // Accept "Bearer <token>" or raw token
        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        AuthToken t = tokens.get(token);
        if (t == null) return Optional.empty();
        return Optional.of(t.userId());
    }

    /* -------------------------
       DTOs
       ------------------------- */
    static record RegisterRequest(String email, String password, String name) {}
    static record LoginRequest(String email, String password) {}
    static record LoginResponse(String token, Long userId) {}
    static record CreateProjectRequest(String name) {}
    static record AddPromptRequest(String prompt) {}
    static record ChatRequest(String message) {}

    /* -------------------------
       Auth Controller
       ------------------------- */
    @RestController
    @RequestMapping("/auth")
    static class AuthController {

        @PostMapping("/register")
        public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
            if (req == null || req.email() == null || req.password() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "email and password required"));
            }
            String email = req.email().toLowerCase(Locale.ROOT).trim();
            if (emailToUserId.containsKey(email)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "email already exists"));
            }
            long id = userSeq++;
            User u = new User(id, email, simpleHash(req.password()), req.name() == null ? "" : req.name());
            users.put(id, u);
            emailToUserId.put(email, id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", id, "email", email));
        }

        @PostMapping("/login")
        public ResponseEntity<?> login(@RequestBody LoginRequest req) {
            if (req == null || req.email() == null || req.password() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "email and password required"));
            }
            Long uid = emailToUserId.get(req.email().toLowerCase(Locale.ROOT).trim());
            if (uid == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid"));
            User u = users.get(uid);
            if (!Objects.equals(u.passwordHash(), simpleHash(req.password()))) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid"));
            }
            String token = generateToken();
            tokens.put(token, new AuthToken(token, uid, Instant.now()));
            return ResponseEntity.ok(new LoginResponse(token, uid));
        }

        @PostMapping("/logout")
        public ResponseEntity<?> logout(@RequestHeader(value="Authorization", required=false) String authorization) {
            String token = authorization == null ? null : (authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization);
            if (token != null) tokens.remove(token);
            return ResponseEntity.ok(Map.of("ok", true));
        }
    }

    /* -------------------------
       Projects Controller
       ------------------------- */
    @RestController
    @RequestMapping("/projects")
    static class ProjectController {

        @GetMapping
        public ResponseEntity<?> list(@RequestHeader(value="Authorization", required=false) String authorization) {
            Optional<Long> maybeUser = userIdFromAuthHeader(authorization);
            if (maybeUser.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "auth required"));
            Long uid = maybeUser.get();
            List<Project> list = projects.values().stream()
                    .filter(p -> Objects.equals(p.ownerId(), uid))
                    .collect(Collectors.toList());
            return ResponseEntity.ok(list);
        }

        @PostMapping
        public ResponseEntity<?> create(@RequestHeader(value="Authorization", required=false) String authorization,
                                        @RequestBody CreateProjectRequest req) {
            Optional<Long> maybeUser = userIdFromAuthHeader(authorization);
            if (maybeUser.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "auth required"));
            if (req == null || req.name() == null || req.name().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "project name required"));
            }
            long id = projectSeq++;
            Project p = new Project(id, maybeUser.get(), req.name().trim(), Collections.synchronizedList(new ArrayList<>()));
            projects.put(id, p);
            return ResponseEntity.status(HttpStatus.CREATED).body(p);
        }

        @PostMapping("/{projectId}/prompts")
        public ResponseEntity<?> addPrompt(@RequestHeader(value="Authorization", required=false) String authorization,
                                           @PathVariable("projectId") Long projectId,
                                           @RequestBody AddPromptRequest body) {
            Optional<Long> maybeUser = userIdFromAuthHeader(authorization);
            if (maybeUser.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "auth required"));
            Project p = projects.get(projectId);
            if (p == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error","project not found"));
            if (!Objects.equals(p.ownerId(), maybeUser.get())) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error","not owner"));
            if (body == null || body.prompt() == null || body.prompt().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error","prompt required"));
            }
            p.prompts().add(body.prompt().trim());
            return ResponseEntity.ok(Map.of("ok", true, "prompts", p.prompts()));
        }
    }

    /* -------------------------
       Chat Controller (simulates an LLM)
       ------------------------- */
    @RestController
    @RequestMapping("/chat")
    static class ChatController {
        // Quick browser test (GET)
        @GetMapping
        public ResponseEntity<?> chatGet(@RequestHeader(value="Authorization", required=false) String authorization,
                                         @RequestParam(name="project", required=false) Long projectId,
                                         @RequestParam(name="msg", required=false) String msg) {
            Optional<Long> maybeUser = userIdFromAuthHeader(authorization);
            if (maybeUser.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "auth required"));
            if (msg == null) msg = "hello";
            return ResponseEntity.ok(simulateReply(maybeUser.get(), projectId, msg));
        }

        // POST chat with plain text body
        @PostMapping(consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
        public ResponseEntity<String> chatPost(@RequestHeader(value="Authorization", required=false) String authorization,
                                               @RequestParam(name="project", required=false) Long projectId,
                                               @RequestBody String message) {
            Optional<Long> maybeUser = userIdFromAuthHeader(authorization);
            if (maybeUser.isEmpty()) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("error: auth required");
            String reply = simulateReply(maybeUser.get(), projectId, message == null ? "" : message);
            return ResponseEntity.ok(reply);
        }

        // Very small simulated reply builder
        private String simulateReply(Long userId, Long projectId, String message) {
            StringBuilder sb = new StringBuilder();
            sb.append("🤖 Simulated reply:\n");

            if (projectId != null) {
                Project p = projects.get(projectId);
                if (p == null) {
                    sb.append("Project not found (id=").append(projectId).append("). ");
                } else if (!Objects.equals(p.ownerId(), userId)) {
                    sb.append("You are not the owner of the project. ");
                } else {
                    sb.append("Project: ").append(p.name()).append("\n");
                    if (!p.prompts().isEmpty()) {
                        sb.append("Using prompts (most recent first):\n");
                        List<String> rev = new ArrayList<>(p.prompts());
                        Collections.reverse(rev);
                        for (int i = 0; i < Math.min(3, rev.size()); i++) {
                            sb.append("- ").append(rev.get(i)).append("\n");
                        }
                    } else {
                        sb.append("(no prompts stored)\n");
                    }
                }
            } else {
                sb.append("(no project specified)\n");
            }

            sb.append("\nUser message: ").append(message.trim()).append("\n");
            // Fake "intelligence": echo with small transformations
            String cleaned = message.trim();
            String echo = cleaned.isEmpty() ? "..." : cleaned;
            sb.append("\nAnswer: I heard you say '").append(echo).append("'. ");
            // make a tiny "improvement"
            if (echo.length() < 40) sb.append("Here's a short suggestion: ").append(echo).append(" ✅");
            else sb.append("Summary: ").append(echo.substring(0, Math.min(120, echo.length()))).append("...");
            return sb.toString();
        }
    }

    /* -------------------------
       Small convenience: sample data on startup (only if empty)
       ------------------------- */
    static {
        // seed one demo user and project for quick demo
        long id = userSeq++;
        User demo = new User(id, "demo@example.com", simpleHash("demo123"), "Demo User");
        users.put(id, demo);
        emailToUserId.put("demo@example.com", id);

        long pid = projectSeq++;
        Project p = new Project(pid, id, "Demo Project", Collections.synchronizedList(new ArrayList<>()));
        p.prompts().add("You are a helpful assistant.");
        p.prompts().add("When asked, provide concise examples.");
        projects.put(pid, p);
    }

    /* -------------------------
       Small homepage mapping (quick)
       ------------------------- */
    @RestController
    static class Home {
        @GetMapping("/")
        public String home() {
            return """
                <h2>✅ Simple Chatbot Platform (demo)</h2>
                <p>Seeded user: <b>demo@example.com</b> / password <b>demo123</b></p>
                <ul>
                  <li>Register: POST /auth/register { "email","password","name" }</li>
                  <li>Login: POST /auth/login { "email","password" } → returns token</li>
                  <li>List Projects: GET /projects (Authorization: Bearer &lt;token&gt;)</li>
                  <li>Create Project: POST /projects (Authorization header)</li>
                  <li>Add Prompt: POST /projects/{projectId}/prompts (body {\"prompt\":\"...\"})</li>
                  <li>Chat (GET): GET /chat?msg=hello&project={id} (Authorization header)</li>
                  <li>Chat (POST): POST /chat?project={id} with text/plain body</li>
                </ul>
                """;
        }
    }
}
