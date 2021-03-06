package jetbrains.macros.actions;
/**
 * Created by Egor.Malyshev on 12.03.2015.
 */

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.atlassian.sal.api.user.UserManager;
import com.atlassian.templaterenderer.TemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import youtrack.YouTrack;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static jetbrains.macros.util.Strings.*;

public class ConfigurationServlet extends HttpServlet {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationServlet.class);
    private final UserManager userManager;
    private final LoginUriProvider loginUriProvider;
    private final TemplateRenderer renderer;
    private final ApplicationProperties applicationProperties;
    private final PluginSettingsFactory pluginSettingsFactory;
    private final TransactionTemplate transactionTemplate;
    private int justSaved = -1;

    public ConfigurationServlet(UserManager userManager,
                                LoginUriProvider loginUriProvider,
                                TemplateRenderer renderer,
                                ApplicationProperties applicationProperties,
                                PluginSettingsFactory pluginSettingsFactory,
                                TransactionTemplate transactionTemplate) {
        this.userManager = userManager;
        this.loginUriProvider = loginUriProvider;
        this.renderer = renderer;
        this.applicationProperties = applicationProperties;
        this.pluginSettingsFactory = pluginSettingsFactory;
        this.transactionTemplate = transactionTemplate;
    }

    private void checkAdminRights(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        final String username = userManager.getRemoteUsername(req);
        if (username == null || !userManager.isAdmin(username)) {
            redirectToLogin(req, resp);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        checkAdminRights(req, resp);
        final String hostAddressPassed = req.getParameter(HOST);
        final String hostAddress = hostAddressPassed.endsWith(URL_SEPARATOR) ? hostAddressPassed : hostAddressPassed + URL_SEPARATOR;
        final String password = req.getParameter(PASSWORD);
        final String login = req.getParameter(LOGIN);
        final String token = req.getParameter(AUTH_KEY);
        final String useToken = req.getParameter(USE_TOKEN) != null ? "true" : "false";
        final String retries = req.getParameter(RETRIES);

        String linkbase = req.getParameter(LINKBASE);
        if (linkbase == null || linkbase.isEmpty())
            linkbase = hostAddress.replace(REST_PREFIX, EMPTY) + URL_SEPARATOR;
        final String trustAll = req.getParameter(TRUST_ALL) != null ? "true" : "false";
        final String extendedDebug = req.getParameter(EXTENDED_DEBUG) != null ? "true" : "false";
        final YouTrack testYouTrack = YouTrack.getInstance(hostAddress, Boolean.parseBoolean(trustAll));
        try {
            final boolean useTokenAuthorization = Boolean.parseBoolean(useToken);
            testYouTrack.setUseTokenAuthorization(useTokenAuthorization);
            testYouTrack.setAuthorization(token);
            if (!useTokenAuthorization) {
                testYouTrack.login(login, password);
            }
            final String finalLinkbase = linkbase;

            transactionTemplate.execute(new TransactionCallback<Properties>() {
                @Override
                public Properties doInTransaction() {
                    final PluginSettings pluginSettings = pluginSettingsFactory.createGlobalSettings();
                    final Properties storage = new Properties();
                    storage.setProperty(HOST, hostAddress);
                    storage.setProperty(EXTENDED_DEBUG, extendedDebug);

                    storage.setProperty(USE_TOKEN, useToken);

                    if (useTokenAuthorization) {
                        storage.setProperty(AUTH_KEY, token);
                    } else {
                        storage.setProperty(LOGIN, login);
                        storage.setProperty(PASSWORD, password);
                        storage.setProperty(AUTH_KEY, testYouTrack.getAuthorization());
                    }

                    storage.setProperty(RETRIES, intValueOf(retries, 10));
                    storage.setProperty(TRUST_ALL, trustAll);
                    storage.setProperty(LINKBASE, finalLinkbase);
                    pluginSettings.put(MAIN_KEY, storage);
                    return storage;
                }
            });
            justSaved = 0;
        } catch (Exception e) {
            LOG.error("YouTrack integration login failed.", e);
            e.printStackTrace();
            justSaved = -2;
        }
        doGet(req, resp);
    }

    private String intValueOf(String retries, int defaultValue) {
        try {
            Integer i = Integer.parseInt(retries);
            return String.valueOf(i);
        } catch (NumberFormatException nfe) {
            return String.valueOf(defaultValue);
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        checkAdminRights(request, response);
        final Map<String, Object> params = new HashMap<String, Object>();
        params.put("baseUrl", applicationProperties.getBaseUrl());
        Properties storage = transactionTemplate.execute(new TransactionCallback<Properties>() {
            @Override
            public Properties doInTransaction() {
                final PluginSettings pluginSettings = pluginSettingsFactory.createGlobalSettings();
                return (Properties) pluginSettings.get(MAIN_KEY);
            }
        });

        if (storage == null) storage = new Properties();

        params.put(HOST, storage.getProperty(HOST, EMPTY));
        params.put(RETRIES, storage.getProperty(RETRIES, "10"));
        params.put(PASSWORD, storage.getProperty(PASSWORD, EMPTY));
        params.put(LOGIN, storage.getProperty(LOGIN, EMPTY));
        params.put(AUTH_KEY, storage.getProperty(AUTH_KEY, EMPTY));
        params.put(USE_TOKEN, storage.getProperty(USE_TOKEN, "true"));
        params.put(EXTENDED_DEBUG, storage.getProperty(EXTENDED_DEBUG, "false"));
        params.put(TRUST_ALL, storage.getProperty(TRUST_ALL, "false"));
        params.put(LINKBASE, storage.getProperty(LINKBASE, EMPTY));

        params.put("justSaved", justSaved);
        justSaved = -1;
        response.setContentType("text/html;charset=utf-8");
        renderer.render("/templates/settings-servlet.vm", params, response.getWriter());
    }

    private void redirectToLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(loginUriProvider.getLoginUri(getUri(request)).toASCIIString());
    }

    private URI getUri(HttpServletRequest request) {
        final StringBuffer builder = request.getRequestURL();
        if (request.getQueryString() != null) {
            builder.append("?");
            builder.append(request.getQueryString());
        }
        return URI.create(builder.toString());
    }
}