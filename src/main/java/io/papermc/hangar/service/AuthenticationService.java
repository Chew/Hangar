package io.papermc.hangar.service;

import io.papermc.hangar.config.hangar.HangarConfig;
import io.papermc.hangar.controller.util.ApiScope;
import io.papermc.hangar.db.dao.HangarDao;
import io.papermc.hangar.db.dao.UserDao;
import io.papermc.hangar.db.dao.api.SessionsDao;
import io.papermc.hangar.db.model.ApiKeysTable;
import io.papermc.hangar.db.model.ApiSessionsTable;
import io.papermc.hangar.db.model.UsersTable;
import io.papermc.hangar.model.ApiAuthInfo;
import io.papermc.hangar.model.Permission;
import io.papermc.hangar.model.generated.SessionType;
import io.papermc.hangar.security.HangarAuthentication;
import io.papermc.hangar.util.AuthUtils;
import io.papermc.hangar.db.dao.ApiKeyDao;
import io.papermc.hangar.model.Role;
import io.papermc.hangar.model.generated.ApiSessionResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.papermc.hangar.util.AuthUtils.unAuth;

@Service
public class AuthenticationService {

    private final HttpServletRequest request;
    private final ApiAuthInfo apiAuthInfo;
    private final HangarConfig hangarConfig;
    private final HangarDao<UserDao> userDao;
    private final HangarDao<SessionsDao> sessionsDao;
    private final HangarDao<ApiKeyDao> apiKeyDao;
    private final AuthenticationManager authenticationManager;
    private final RoleService roleService;
    private final PermissionService permissionService;

    private static final String UUID_REGEX = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern API_KEY_PATTERN = Pattern.compile("(" + UUID_REGEX + ").(" + UUID_REGEX + ")");

    @Autowired
    public AuthenticationService(HttpServletRequest request, ApiAuthInfo apiAuthInfo, HangarConfig hangarConfig, HangarDao<UserDao> userDao, HangarDao<SessionsDao> sessionsDao, HangarDao<ApiKeyDao> apiKeyDao, AuthenticationManager authenticationManager, RoleService roleService, PermissionService permissionService) {
        this.request = request;
        this.apiAuthInfo = apiAuthInfo;
        this.hangarConfig = hangarConfig;
        this.userDao = userDao;
        this.sessionsDao = sessionsDao;
        this.apiKeyDao = apiKeyDao;
        this.authenticationManager = authenticationManager;
        this.roleService = roleService;
        this.permissionService = permissionService;
    }

    public ApiAuthInfo getApiAuthInfo(String token) {
        return apiKeyDao.get().getApiAuthInfo(token);
    }

    @Bean
    @RequestScope
    public ApiAuthInfo apiAuthInfo() {
        if (request.getRequestURI().startsWith("/api/v2")) { // if api method
            AuthUtils.AuthCredentials credentials = AuthUtils.parseAuthHeader(request, true);
            if (credentials.getSession() == null) {
                throw AuthUtils.unAuth("No session specified");
            }
            ApiAuthInfo info = apiKeyDao.get().getApiAuthInfo(credentials.getSession());
            if (info == null) {
                throw AuthUtils.unAuth("Invalid session");
            }
            if (info.getExpires().isBefore(OffsetDateTime.now())) {
                sessionsDao.get().delete(credentials.getSession());
                throw AuthUtils.unAuth("Api session expired");
            }
            return info;
        }
        return null;
    }

    public boolean authApiRequest(Permission perms, ApiScope apiScope) {
        switch (apiScope.getType()) {
            case GLOBAL:
                if (!apiAuthInfo.getGlobalPerms().has(perms)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                }
                return true;
            case PROJECT:
                if (apiScope.getValue() == null || apiScope.getValue().isBlank()) {
                    throw new IllegalStateException("Must have passed the pluginId to apiAction");
                }
                if (!permissionService.getProjectPermissions(apiAuthInfo.getUser(), apiScope.getValue()).has(perms)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                }
                return true;
            case ORGANIZATION:
                if (apiScope.getValue() == null || apiScope.getValue().isBlank()) {
                    throw new IllegalStateException("Must have passed the org name to apiAction");
                }
                if (!permissionService.getOrganizationPermissions(apiAuthInfo.getUser(), apiScope.getValue()).has(perms)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN);
                }
                return true;
            default:
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    public ApiSessionResponse authenticateDev() {
        if (hangarConfig.fakeUser.isEnabled()) {
            hangarConfig.checkDebug();
            OffsetDateTime sessionExpiration = AuthUtils.expiration(hangarConfig.api.session.getExpiration(), null);
            String uuidToken = UUID.randomUUID().toString();

            ApiSessionsTable apiSession = new ApiSessionsTable(uuidToken, null, hangarConfig.fakeUser.getId(), sessionExpiration);
            saveSession(apiSession);

            return new ApiSessionResponse(apiSession.getToken(), apiSession.getExpires(), SessionType.DEV);
        } else {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    public ApiSessionResponse authenticateKeyPublic(Long expiresIn) {
        OffsetDateTime sessionExpiration = AuthUtils.expiration(hangarConfig.api.session.getExpiration(), expiresIn);
        OffsetDateTime publicSessionExpiration = AuthUtils.expiration(hangarConfig.api.session.getPublicExpiration(), expiresIn);
        String uuidToken = UUID.randomUUID().toString();

        AuthUtils.AuthCredentials credentials = AuthUtils.parseAuthHeader(false);
        SessionType sessionType;
        ApiSessionsTable apiSession;
        if (credentials.getApiKey() != null) {
            if (!API_KEY_PATTERN.matcher(credentials.getApiKey()).find()) {
                throw AuthUtils.unAuth("No valid apikey parameter found in Authorization");
            }
            if (sessionExpiration == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The requested expiration can't be used");
            }
            // I THINK that is how its setup, couldn't really figure it out via ore
            String identifier = credentials.getApiKey().split("\\.")[0];
            String token = credentials.getApiKey().split("\\.")[1];
            ApiKeysTable apiKeysTable = apiKeyDao.get().findApiKey(identifier, token);
            if (apiKeysTable == null) {
                throw AuthUtils.unAuth("Invalid api key");
            }
            apiSession = new ApiSessionsTable(uuidToken, apiKeysTable.getId(), apiKeysTable.getOwnerId(), sessionExpiration);
            sessionType = SessionType.KEY;
        } else {
            if (publicSessionExpiration == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The requested expiration can't be used");
            }
            apiSession = new ApiSessionsTable(uuidToken, null, null, publicSessionExpiration);
            sessionType = SessionType.PUBLIC;
        }

        sessionsDao.get().insert(apiSession);

        return new ApiSessionResponse(apiSession.getToken(), apiSession.getExpires(), sessionType);
    }

    public ApiSessionResponse authenticateUser(long userId) {
        OffsetDateTime sessionExpiration = AuthUtils.expiration( hangarConfig.api.session.getExpiration(), null);
        String uuidToken = UUID.randomUUID().toString();

        ApiSessionsTable apiSession = new ApiSessionsTable(uuidToken, null, userId, sessionExpiration);
        saveSession(apiSession);

        return new ApiSessionResponse(apiSession.getToken(), apiSession.getExpires(), SessionType.USER);
    }

    private ApiSessionsTable saveSession(ApiSessionsTable apiSession) {
        return sessionsDao.get().insert(apiSession);
    }

    public void loginAsFakeUser() {
        String username = hangarConfig.fakeUser.getUsername();
        UsersTable userEntry = userDao.get().getByName(username);
        if (userEntry == null) {
            userEntry = new UsersTable();
            userEntry.setEmail(hangarConfig.fakeUser.getEmail());
            userEntry.setFullName(hangarConfig.fakeUser.getName());
            userEntry.setName(hangarConfig.fakeUser.getUsername());
            userEntry.setId(hangarConfig.fakeUser.getId());
            userEntry.setReadPrompts(List.of());

            userEntry = userDao.get().insert(userEntry);

            roleService.addGlobalRole(userEntry.getId(), Role.HANGAR_ADMIN.getRoleId());
        }
        setAuthenticatedUser(userEntry);
    }

    public void setAuthenticatedUser(UsersTable user) {
        // TODO properly do auth, remember me shit too
        Authentication auth = new HangarAuthentication(List.of(new SimpleGrantedAuthority("ROLE_USER")), user.getName(), user.getId(), user);
        authenticationManager.authenticate(auth);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

}
