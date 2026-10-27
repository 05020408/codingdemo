package cn.review.auth;

import cn.review.common.Api;
import cn.review.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final AuthenticationManager manager;
    private final SessionAuthenticationStrategy sessions;
    private final HttpSessionSecurityContextRepository contexts;
    public AuthController(UserMapper users, PasswordEncoder passwords, AuthenticationManager manager, SessionAuthenticationStrategy sessions, HttpSessionSecurityContextRepository contexts) {
        this.users=users; this.passwords=passwords; this.manager=manager; this.sessions=sessions; this.contexts=contexts;
    }
    @GetMapping("/csrf")
    Api<Map<String,String>> csrf(CsrfToken token, HttpServletRequest request) { return Api.ok(Map.of("headerName",token.getHeaderName(),"token",token.getToken()),request); }
    @PostMapping("/register")
    ResponseEntity<Api<UserRow.UserView>> register(@RequestBody Registration input, HttpServletRequest request) {
        String name=ApiException.text(input.username(),"账号",3,32);
        if (!name.matches("[A-Za-z0-9_]+")) throw new ApiException(400,"VALIDATION_FAILED","账号只能包含字母、数字和下划线");
        String password=input.password();
        if (password==null || password.codePointCount(0,password.length())<8 || password.codePointCount(0,password.length())>64 || password.getBytes(StandardCharsets.UTF_8).length>72 || password.isBlank()) {
            throw new ApiException(400,"VALIDATION_FAILED","密码需为 8 至 64 个字符，且 UTF-8 编码不超过 72 字节");
        }
        String display=input.displayName()==null || input.displayName().isBlank()?name:ApiException.text(input.displayName(),"显示名",1,50);
        UserRow row=new UserRow(UUID.randomUUID().toString(),name,name.toLowerCase(Locale.ROOT),passwords.encode(password),display,"ACTIVE");
        try { users.insert(row); } catch(DuplicateKeyException error) { throw new ApiException(409,"USERNAME_TAKEN","该账号已注册，请更换账号或登录"); }
        return ResponseEntity.status(201).body(Api.ok(row.view(),request));
    }
    @PostMapping("/login")
    Api<UserRow.UserView> login(@RequestBody Login input,HttpServletRequest request,HttpServletResponse response) {
        String name=ApiException.text(input.username(),"账号",3,32);
        if(input.password()==null || input.password().getBytes(StandardCharsets.UTF_8).length>72) throw new ApiException(400,"VALIDATION_FAILED","请检查账号和密码");
        try {
            Authentication auth=manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(name,input.password()));
            sessions.onAuthentication(auth,request,response);
            var context=SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
            SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
            return Api.ok(((StudentPrincipal)auth.getPrincipal()).user().view(),request);
        } catch(AuthenticationException error) { throw new ApiException(401,"INVALID_CREDENTIALS","账号或密码不正确"); }
    }
    @GetMapping("/me")
    Api<UserRow.UserView> me(@AuthenticationPrincipal StudentPrincipal student,HttpServletRequest request) { return Api.ok(student.user().view(),request); }
    public record Registration(String username,String password,String displayName) {}
    public record Login(String username,String password) {}
}
