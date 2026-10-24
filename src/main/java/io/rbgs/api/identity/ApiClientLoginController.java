package io.rbgs.api.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local")
@RequiredArgsConstructor
public class ApiClientLoginController {
    public static final String RETURN_TO_API_CLIENT = ApiClientLoginController.class.getName() + ".return";
    private final ApiClientLoginService logins;
    private final ObjectProvider<OAuth2AuthorizedClientRepository> clientRepositories;
    private final AccountService accounts;
    public record Secret(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String secret) { }
    public record Result(String status, String sessionId, String csrfToken) { }

    @PostMapping("/api/v1/auth/api-client/requests")
    public ResponseEntity<ApiClientLoginService.Started> start(HttpServletRequest request) {
        nativeOnly(request);
        return response(logins.start(request.getRemoteAddr()));
    }
    @GetMapping("/api/v1/auth/api-client/login")
    public ResponseEntity<Void> login(HttpServletRequest request) {
        request.getSession().setAttribute(RETURN_TO_API_CLIENT, true);
        return ResponseEntity.status(302).header("Location", "/oauth2/authorization/battle-net")
                .cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/api/v1/auth/api-client/approve")
    public ResponseEntity<Void> approve(@Valid @RequestBody Secret body, Authentication authentication,
            HttpServletRequest request) {
        OAuth2AuthorizedClient client = clients().loadAuthorizedClient("battle-net", authentication, request);
        logins.approve(body.secret(), authentication, client);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PostMapping("/api/v1/auth/api-client/token")
    public ResponseEntity<Result> exchange(@Valid @RequestBody Secret body, HttpServletRequest request,
            HttpServletResponse servletResponse) {
        nativeOnly(request);
        if (request.getSession(false) != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Send this request without session cookies");
        var ticket = logins.poll(body.secret(), request.getRemoteAddr());
        synchronized (ticket) {
            if (ticket.consumed) throw ApiClientLoginService.gone();
            if (ticket.authentication == null) return response(new Result("PENDING", null, null));
            if (!(ticket.authentication.getPrincipal() instanceof org.springframework.security.oauth2.core.oidc.user.OidcUser user))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            try {
                var account = accounts.findByIdentity(user.getIdToken().getIssuer().toString(), user.getSubject());
                if (!"ACTIVE".equals(account.status())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            } catch (java.util.NoSuchElementException missing) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(ticket.authentication);
            request.getSession(true);
            new HttpSessionSecurityContextRepository().saveContext(context, request, servletResponse);
            clients().saveAuthorizedClient(ticket.client, ticket.authentication, request, servletResponse);
            var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
            var csrf = csrfRepository.generateToken(request);
            csrfRepository.saveToken(csrf, request, servletResponse);
            String sessionId = request.getSession(false).getId();
            logins.consume(body.secret(), ticket);
            return response(new Result("PAIRED", sessionId, csrf.getToken()));
        }
    }
    private static void nativeOnly(HttpServletRequest request) {
        if (request.getHeader("Origin") != null || request.getHeader("Sec-Fetch-Site") != null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    private OAuth2AuthorizedClientRepository clients() {
        var repository = clientRepositories.getIfAvailable();
        if (repository == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Battle.net login is not configured");
        return repository;
    }
    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    @GetMapping(value = "/api/v1/auth/api-client", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            .body("""
                <!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>Vincular cliente de API · rbgs.io</title>
                <style>body{font:18px system-ui;max-width:620px;margin:10vh auto;padding:24px}button,a{font:inherit}button{padding:12px}small{display:block;margin:24px 0}</style>
                <h1>Vincular cliente de API</h1><p id="status">Comprobando sesión…</p>
                <small>Aprueba solo si acabas de crear esta solicitud en tu cliente de API.
                Darás acceso a tu cuenta y a las acciones de tu rol durante la sesión.</small>
                <a id="login" href="/api/v1/auth/api-client/login" hidden>Iniciar sesión con Battle.net</a>
                <button id="approve" hidden>Autorizar mi cliente de API</button>
                <script>
                const key='rbgs-api-client-approval';
                if(location.hash) { sessionStorage.setItem(key,JSON.stringify({secret:location.hash.slice(1),until:Date.now()+300000})); history.replaceState(null,'',location.pathname); }
                const status=document.getElementById('status'), button=document.getElementById('approve');
                let pending; try { pending=JSON.parse(sessionStorage.getItem(key)); } catch(e) {}
                const secret=pending && pending.until>Date.now() ? pending.secret : null;
                if(!secret) { status.textContent='Solicitud ausente o caducada. Crea otra desde tu cliente de API.'; sessionStorage.removeItem(key); }
                else fetch('/api/v1/auth/me',{credentials:'same-origin'}).then(async r=>{
                  if(r.status===204 || r.status===401) {status.textContent='Inicia sesión para elegir la cuenta que vincularás.';document.getElementById('login').hidden=false;return;}
                  if(!r.ok) throw Error(); const user=await r.json();
                  status.textContent='Cuenta: '+user.displayName+' ('+user.role+'). Servidor: '+location.origin;
                  button.hidden=false;
                }).catch(()=>status.textContent='No se pudo comprobar la sesión. Recarga la página.');
                button.onclick=async()=>{button.disabled=true;try {
                  const cookie=document.cookie.split('; ').find(c=>c.startsWith('XSRF-TOKEN='));
                  const token=cookie ? decodeURIComponent(cookie.slice('XSRF-TOKEN='.length)) : '';
                  const r=await fetch('/api/v1/auth/api-client/approve',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-XSRF-TOKEN':token},body:JSON.stringify({secret})});
                  if(!r.ok) throw Error();sessionStorage.removeItem(key);button.hidden=true;
                  status.textContent='Autorizado. Vuelve a tu cliente de API para recoger la sesión.';
                }catch(e){status.textContent='No se pudo autorizar. La solicitud puede haber caducado; inicia otra desde tu cliente de API.';button.disabled=false;}};
                </script></html>
                """);
    }
}
