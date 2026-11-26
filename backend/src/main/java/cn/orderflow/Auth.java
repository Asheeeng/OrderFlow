package cn.orderflow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

record Actor(long id, String username, String name, String role, Long merchantId, String csrf) {}

@Service
class Auth {
    private static final String SESSION_USER = "userId";
    private static final String SESSION_CSRF = "csrf";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String missingHash = encoder.encode(UUID.randomUUID().toString());
    private final UserMapper users;
    private final JdbcTemplate db;

    Auth(UserMapper users, JdbcTemplate db) {
        this.users = users;
        this.db = db;
    }

    Actor login(HttpServletRequest request, String username, String password) {
        UserAccount user = users.selectOne(new QueryWrapper<UserAccount>().eq("username", username));
        boolean hasLogin = user != null && user.passwordHash.startsWith("$2");
        boolean matches = password.getBytes(StandardCharsets.UTF_8).length <= 72
                && encoder.matches(password, hasLogin ? user.passwordHash : missingHash);
        if (!matches || !hasLogin || !user.active) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误，或账号已停用");
        }
        String csrf = UUID.randomUUID().toString();
        Actor signedIn = actor(user, csrf);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(SESSION_USER, user.id);
        session.setAttribute(SESSION_CSRF, csrf);
        return signedIn;
    }

    void register(String username, String password, String name) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "密码最多 72 字节");
        }
        UserAccount user = new UserAccount();
        user.username = username;
        user.displayName = name.trim();
        user.passwordHash = encoder.encode(password);
        user.role = "CUSTOMER";
        user.active = true;
        users.insert(user);
    }

    Actor require(HttpServletRequest request, String... allowedRoles) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(SESSION_USER) instanceof Long id)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        String csrf = (String) session.getAttribute(SESSION_CSRF);
        if (!request.getMethod().equals("GET")) {
            String supplied = request.getHeader("X-OrderFlow-CSRF");
            if (supplied == null || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "会话校验失败");
            }
        }
        Actor actor = actor(users.selectById(id), csrf);
        if (allowedRoles.length > 0 && !java.util.Arrays.asList(allowedRoles).contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "当前角色无权执行此操作");
        }
        return actor;
    }

    private Actor actor(UserAccount user, String csrf) {
        if (user == null || !user.active) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "账号已停用");
        }
        Long merchant = null;
        if (user.role.equals("MERCHANT")) {
            merchant = db.query("SELECT id FROM merchants WHERE user_id=? AND active=1",
                    rs -> rs.next() ? rs.getLong(1) : null, user.id);
            if (merchant == null) {
                throw new ApiException(HttpStatus.FORBIDDEN, "商家已停用");
            }
        }
        return new Actor(user.id, user.username, user.displayName, user.role, merchant, csrf);
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    record Login(@NotBlank @Size(max = 40) String username, @NotBlank @Size(max = 72) String password) {}
    record Registration(@Pattern(regexp = "[A-Za-z0-9_]{3,40}") @NotBlank String username,
                        @NotBlank @Size(min = 8, max = 72) String password,
                        @NotBlank @Size(max = 80) String name) {}

    private final Auth auth;

    AuthController(Auth auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    Actor login(HttpServletRequest request, @Valid @RequestBody Login form) {
        return auth.login(request, form.username(), form.password());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> register(@Valid @RequestBody Registration form) {
        auth.register(form.username(), form.password(), form.name());
        return Map.of("message", "注册成功");
    }

    @GetMapping("/me")
    Actor me(HttpServletRequest request) {
        return auth.require(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request) {
        auth.require(request);
        request.getSession(false).invalidate();
    }
}
