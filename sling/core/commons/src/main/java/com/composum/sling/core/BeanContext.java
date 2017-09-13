package com.composum.sling.core;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.sling.adapter.annotations.Adapter;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.adapter.Adaptable;
import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.api.scripting.SlingScriptHelper;
import org.apache.sling.commons.classloader.DynamicClassLoaderManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.jsp.PageContext;
import java.util.*;

/**
 * The interface for the different scripting contexts (JSP, Groovy, ...) and the basic implementations for this
 * interface. This serves as a container for the basic objects often used to initialize models.
 */
@org.apache.sling.adapter.annotations.Adaptable(adaptableClass = BeanContext.class,
        adapters = @Adapter(condition = "If the context contains an entity of the requested type",
                value = {Resource.class, ResourceResolver.class,
                        SlingHttpServletRequest.class, SlingHttpServletResponse.class})
)
public interface BeanContext extends Adaptable {

    //
    // the attribute names of the main context attributes
    //
    String ATTR_RESOURCE = "resource";
    String ATTR_RESOLVER = "resourceResolver";
    String ATTR_REQUEST = "request";
    String ATTR_RESPONSE = "response";

    /**
     * the Scope enumeration according to the JSPs PageContext
     */
    enum Scope {

        page(PageContext.PAGE_SCOPE),
        request(PageContext.REQUEST_SCOPE),
        session(PageContext.SESSION_SCOPE),
        application(PageContext.APPLICATION_SCOPE);

        public final int value;

        Scope(int value) {
            this.value = value;
        }
    }

    /**
     * Returns the resource declared in the context.
     */
    Resource getResource();

    /**
     * Returns the resolver declared in the context.
     */
    ResourceResolver getResolver();

    /**
     * Returns the request declared in the context.
     */
    SlingHttpServletRequest getRequest();

    /**
     * Returns the response declared in the context.
     */
    SlingHttpServletResponse getResponse();

    /**
     * Returns the locale declared determined using the context.
     */
    Locale getLocale();

    /**
     * Returns an attribute value from the context.
     */
    <T> T getAttribute(String name, Class<T> T);

    /**
     * Stores an attribute in the context in th given scope.
     */
    void setAttribute(String name, Object value, Scope scope);

    /**
     * retrieves a service implementation using the 'sling' script helper
     */
    <T> T getService(Class<T> type);

    /**
     * retrieves a set of services appropriate to the filter
     */
    <T> T[] getServices(Class<T> serviceType, String filter) throws InvalidSyntaxException;

    /**
     * retrieves a class using the Slings DynamicClassLoaderManager implementation
     */
    Class<?> getType(String className) throws ClassNotFoundException;

    /**
     * Adapts to the components {@link Resource}, {@link ResourceResolver}, {@link SlingHttpServletRequest}, {@link
     * SlingHttpServletResponse}, {@link Locale}, {@link BeanContext} itself, a {@link ValueMap} for the request, or
     * possibly more if defined in Sling. Cached - multiple calls will always return the same object, except for {@link
     * ValueMap}.
     *
     * @param type not null, the type to be adapted to
     * @return the component of type or whatever Sling has adapters for, or null if there is nothing.
     * @see SlingAdaptable#adaptTo(Class)
     */
    @Override
    <AdapterType> AdapterType adaptTo(Class<AdapterType> type);

    /**
     * Returns a clone of this context with the resource overridden. If the {@link #getResolver()} was null, it will be
     * set to the resource's {@link Resource#getResourceResolver()}, otherwise it will be kept. All other internal
     * structures of this will be referenced by the copy, too.
     *
     * @param resource the resource
     * @return a context with the same type as this, with resource and possibly resolver changed.
     */
    BeanContext cloneWith(Resource resource);

    /**
     * the base class of the context interface with general methods
     */
    abstract class AbstractContext extends SlingAdaptable implements BeanContext {

        protected AbstractContext() {
        }

        /** Copy constructor - sets every attribute from other. */
        protected AbstractContext(AbstractContext other) {
            Validate.isAssignableFrom(getClass(), other.getClass(),
                    "Cannot initialize " + getClass() + " from " + other.getClass());
        }

        protected abstract <T> T retrieveService(Class<T> type);

        @Override
        public <T> T getService(Class<T> type) {
            String typeKey = type.getName();
            T service = getAttribute(typeKey, type);
            if (service == null) {
                service = retrieveService(type);
                setAttribute(typeKey, service, Scope.request);
            }
            return service;
        }

        @Override
        public Class<?> getType(String className) throws ClassNotFoundException {
            Class<?> type = null;
            // use Sling DynamicClassLoader
            DynamicClassLoaderManager dclm = getService(DynamicClassLoaderManager.class);
            if (dclm != null) {
                type = dclm.getDynamicClassLoader().loadClass(className);
            }
            // fallback to default ClassLoader
            if (type == null) {
                type = Class.forName(className);
            }
            return type;
        }

        @Override
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (typeFits(type, BeanContext.class, this, BeanContext.class))
                return type.cast(this);
            if (typeFits(type, ServletRequest.class, getRequest(), SlingHttpServletRequest.class))
                return type.cast(getRequest());
            if (typeFits(type, ServletResponse.class, getResponse(), SlingHttpServletResponse.class))
                return type.cast(getResponse());
            if (typeFits(type, ResourceResolver.class, getResolver(), ResourceResolver.class))
                return type.cast(getResolver());
            if (typeFits(type, Resource.class, getResource(), Resource.class))
                return type.cast(getResource());
            // adaptTo ValueMap as well, to directly support injecting resource attributes in sling-models
            if (ValueMap.class.equals(type))
                return null != getResource() ? type.cast(getResource().adaptTo(ValueMap.class)) : null;
            if (Locale.class.equals(type)) return type.cast(Locale.class);
            return super.adaptTo(type); // fall back to sling mechanisms.
        }

        /**
         * A type fits if it is below some upper bound (we don't want to return something for type, say, Object) and if
         * it is assignable from the real class of the object. If the object is null, we will return null (and pass by
         * the other Sling adaptable mechanisms) if the type is assignable from the default type the object would
         * implement / extend.
         */
        protected <AdapterType> boolean typeFits(Class<?> type, Class<?> upperbound,
                                                 AdapterType object, Class<AdapterType> defaultclass) {
            if (!upperbound.isAssignableFrom(type)) return false;
            if (null != object && type.isAssignableFrom(object.getClass())) return true;
            return type.isAssignableFrom(defaultclass);
        }

    }

    /**
     * the enhancement of the base for scripting contexts
     */
    abstract class AbstractScriptContext extends AbstractContext {

        protected SlingBindings slingBindings;
        protected SlingScriptHelper scriptHelper;

        protected AbstractScriptContext() {
        }

        /** Copy constructor */
        protected AbstractScriptContext(AbstractScriptContext other) {
            super(other);
            this.scriptHelper = other.scriptHelper;
            this.slingBindings = other.slingBindings;
        }


        @Override
        public <T> T retrieveService(Class<T> type) {
            return getScriptHelper().getService(type);
        }

        @Override
        public <T> T[] getServices(Class<T> serviceType, String filter) {
            return getScriptHelper().getServices(serviceType, filter);
        }

        public SlingScriptHelper getScriptHelper() {
            if (scriptHelper == null) {
                scriptHelper = getSlingBindings().getSling();
            }
            return scriptHelper;
        }

        public SlingBindings getSlingBindings() {
            if (slingBindings == null) {
                slingBindings = getAttribute(SlingBindings.class.getName(), SlingBindings.class);
            }
            return slingBindings;
        }
    }

    /**
     * a Map based implementation of the context interface (e.g. for a Groovy script)
     */
    class Map extends AbstractScriptContext {

        private java.util.Map<String, Object> pageScopeMap;
        private java.util.Map<String, Object> requestScopeMap;
        private java.util.Map<String, Object> sessionScopeMap;

        protected transient SlingHttpServletRequest request;
        protected transient Resource resource;
        protected transient ResourceResolver resolver;

        public Map() {
            this(new HashMap<String, Object>());
        }

        public Map(java.util.Map<String, Object> pageScopeMap) {
            this(pageScopeMap, new HashMap<String, Object>());
        }

        public Map(java.util.Map<String, Object> pageScopeMap,
                   java.util.Map<String, Object> requestScopeMap) {
            this(pageScopeMap, requestScopeMap, new HashMap<String, Object>());
        }

        public Map(java.util.Map<String, Object> pageScopeMap, SlingHttpServletRequest request) {
            this(pageScopeMap, null, new HashMap<String, Object>());
            this.request = request;
        }

        public Map(java.util.Map<String, Object> pageScopeMap,
                   java.util.Map<String, Object> requestScopeMap,
                   java.util.Map<String, Object> sessionScopeMap) {
            this.pageScopeMap = pageScopeMap;
            this.requestScopeMap = requestScopeMap;
            this.sessionScopeMap = sessionScopeMap;
        }

        /** Copy constructor. */
        public Map(Map other) {
            super(other);
            this.pageScopeMap = other.pageScopeMap;
            this.requestScopeMap = other.requestScopeMap;
            this.sessionScopeMap = other.sessionScopeMap;
            this.request = other.request;
            this.resource = other.resource;
            this.resolver = other.resolver;
        }

        @Override
        public Resource getResource() {
            if (resource == null) {
                resource = getAttribute(ATTR_RESOURCE, Resource.class);
            }
            return resource;
        }

        @Override
        public ResourceResolver getResolver() {
            if (resolver == null) {
                resolver = getAttribute(ATTR_RESOLVER, ResourceResolver.class);
            }
            return resolver;
        }

        @Override
        public SlingHttpServletRequest getRequest() {
            return getAttribute(ATTR_REQUEST, SlingHttpServletRequest.class);
        }

        @Override
        public SlingHttpServletResponse getResponse() {
            return getAttribute(ATTR_RESPONSE, SlingHttpServletResponse.class);
        }

        @Override
        public Locale getLocale() {
            return Locale.getDefault();
        }

        @Override
        public <T> T getAttribute(String name, Class<T> T) {
            Object attribute = null;
            if (StringUtils.isNotBlank(name)) {
                attribute = pageScopeMap.get(name);
                if (attribute == null) {
                    if (requestScopeMap != null) {
                        attribute = this.requestScopeMap.get(name);
                        if (attribute == null) {
                            attribute = this.sessionScopeMap.get(name);
                        }
                    } else {
                        SlingHttpServletRequest request = getRequest();
                        if (request != null) {
                            attribute = request.getAttribute(name);
                            if (attribute == null) {
                                HttpSession session = request.getSession();
                                if (session != null) {
                                    attribute = session.getAttribute(name);
                                } else {
                                    attribute = this.sessionScopeMap.get(name);
                                }
                            }
                        }
                    }
                }
            }
            return (T) attribute;
        }

        @Override
        public void setAttribute(String name, Object value, Scope scope) {
            if (scope == Scope.page) {
                this.pageScopeMap.put(name, value);
            } else {
                SlingHttpServletRequest request = getRequest();
                if (request != null) {
                    if (scope == Scope.request) {
                        request.setAttribute(name, value);
                    } else {
                        HttpSession session = request.getSession();
                        if (session != null) {
                            // session and application scope stored in the session
                            session.setAttribute(name, value);
                        } else {
                            // fallback to local map id no session found
                            this.sessionScopeMap.put(name, value);
                        }
                    }
                } else {
                    if (scope == Scope.request) {
                        this.requestScopeMap.put(name, value);
                    } else {
                        this.sessionScopeMap.put(name, value);
                    }
                }
            }
        }

        @Override
        public Map cloneWith(Resource resource) {
            Map copy = new Map(this);
            copy.resource = resource;
            if (null == getResolver() && null != resource) resolver = resource.getResourceResolver();
            return copy;
        }
    }

    /**
     * a Service based implementation for a background service or a job execution
     */
    class Service extends Map {

        public Service() {
        }

        /** Copy constructor. */
        public Service(Service other) {
            super(other);
        }

        public Service(ResourceResolver resolver) {
            setAttribute(ATTR_RESOLVER, this.resolver = resolver, Scope.request);
        }

        @Override
        public Service cloneWith(Resource resource) {
            Service copy = new Service(this);
            copy.resource = resource;
            if (null == getResolver() && null != resource) resolver = resource.getResourceResolver();
            return copy;
        }
    }

    /**
     * a JSP PageContext based implementation of the context interface
     */
    class Page extends AbstractScriptContext {

        private PageContext pageContext;

        private transient Resource resource;
        private transient ResourceResolver resolver;

        public Page(PageContext pageContext) {
            this.pageContext = pageContext;
        }

        public Page(Page other) {
            super(other);
            this.pageContext = other.pageContext;
            this.resource = other.resource;
            this.resolver = other.resolver;
        }

        public PageContext getPageContext() {
            return pageContext;
        }

        @Override
        public Resource getResource() {
            if (resource == null) {
                resource = getAttribute(ATTR_RESOURCE, Resource.class);
            }
            return resource;
        }

        @Override
        public ResourceResolver getResolver() {
            if (resolver == null) {
                resolver = getRequest().getResourceResolver();
            }
            return resolver;
        }

        @Override
        public SlingHttpServletRequest getRequest() {
            return (SlingHttpServletRequest) this.pageContext.getRequest();
        }

        @Override
        public SlingHttpServletResponse getResponse() {
            return (SlingHttpServletResponse) this.pageContext.getResponse();
        }

        @Override
        public Locale getLocale() {
            return Locale.getDefault();
        }

        @Override
        public <T> T getAttribute(String name, Class<T> T) {
            Object attribute = null;
            if (StringUtils.isNotBlank(name)) {
                attribute = this.pageContext.findAttribute(name);
            }
            return (T) attribute;
        }

        @Override
        public void setAttribute(String name, Object value, Scope scope) {
            pageContext.setAttribute(name, value, scope.value);
        }

        /**
         * {@inheritDoc} Adapts to {@link PageContext} as well.
         */
        @Override
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (typeFits(type, PageContext.class, pageContext, PageContext.class))
                return type.cast(pageContext);
            return super.adaptTo(type);
        }

        @Override
        public BeanContext cloneWith(Resource resource) {
            Page copy = new Page(this);
            copy.resource = resource;
            if (null == getResolver() && null != resource) resolver = resource.getResourceResolver();
            return copy;
        }
    }

    /**
     * a servlet API based implementation of the context interface for Beans in a Servlet context
     */
    class Servlet extends AbstractContext {

        private ServletContext servletContext;
        protected BundleContext bundleContext;
        private SlingHttpServletRequest request;
        private SlingHttpServletResponse response;

        private transient Resource resource;
        private transient ResourceResolver resolver;

        public Servlet(ServletContext servletContext, BundleContext bundleContext,
                       SlingHttpServletRequest request, SlingHttpServletResponse response) {
            this.servletContext = servletContext;
            this.bundleContext = bundleContext;
            this.request = request;
            this.response = response;
        }

        /** Copy constructor. */
        public Servlet(Servlet other) {
            super(other);
            this.servletContext = other.servletContext;
            this.bundleContext = other.bundleContext;
            this.request = other.request;
            this.response = other.response;
            this.resource = other.resource;
            this.resolver = other.resolver;
        }

        @Override
        public Resource getResource() {
            if (resource == null) {
                resource = this.request.getResource();
            }
            return resource;
        }

        @Override
        public ResourceResolver getResolver() {
            if (resolver == null) {
                resolver = getRequest().getResourceResolver();
            }
            return resolver;
        }

        @Override
        public SlingHttpServletRequest getRequest() {
            return this.request;
        }

        @Override
        public SlingHttpServletResponse getResponse() {
            return this.response;
        }

        @Override
        public Locale getLocale() {
            return Locale.getDefault();
        }

        @Override
        public <T> T getAttribute(String name, Class<T> T) {
            T attribute = null;
            if (StringUtils.isNotBlank(name)) {
                attribute = (T) this.request.getAttribute(name);
                if (attribute == null) {
                    HttpSession session = this.request.getSession();
                    if (session != null) {
                        attribute = (T) session.getAttribute(name);
                    }
                    if (attribute == null) {
                        if (this.servletContext != null) {
                            attribute = (T) this.servletContext.getAttribute(name);
                        }
                    }
                }
            }
            return attribute;
        }

        @Override
        public void setAttribute(String name, Object value, Scope scope) {
            switch (scope) {
                case application:
                    if (this.servletContext != null) {
                        this.servletContext.setAttribute(name, value);
                        break;
                    }
                    // fallback to session if no servlet context present
                case session:
                    HttpSession session = this.request.getSession();
                    if (session != null) {
                        session.setAttribute(name, value);
                        break;
                    }
                    // fallback to request if no session present
                case page:
                    // use request for all attributes in the page scope
                case request:
                    // storing in request scope is the default
                default:
                    request.setAttribute(name, value);
                    break;
            }
        }

        @Override
        public <T> T retrieveService(Class<T> type) {
            ServiceReference<T> reference = bundleContext.getServiceReference(type);
            return bundleContext.getService(reference);
        }

        @Override
        public <T> T[] getServices(Class<T> type, String filter) throws InvalidSyntaxException {
            List<T> services = new ArrayList<>();
            Collection<ServiceReference<T>> references;
            references = bundleContext.getServiceReferences(type, filter);
            for (ServiceReference<T> reference : references) {
                services.add(bundleContext.getService(reference));
            }
            return (T[]) services.toArray();
        }

        /**
         * {@inheritDoc} Adapts to {@link ServletContext} and {@link BundleContext} as well.
         */
        @Override
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (typeFits(type, ServletContext.class, servletContext, ServletContext.class))
                return type.cast(servletContext);
            if (typeFits(type, BundleContext.class, bundleContext, BundleContext.class))
                return type.cast(servletContext);
            return super.adaptTo(type);
        }

        @Override
        public Servlet cloneWith(Resource resource) {
            Servlet copy = new Servlet(this);
            copy.resource = resource;
            if (null == getResolver() && null != resource) resolver = resource.getResourceResolver();
            return copy;
        }
    }
}
