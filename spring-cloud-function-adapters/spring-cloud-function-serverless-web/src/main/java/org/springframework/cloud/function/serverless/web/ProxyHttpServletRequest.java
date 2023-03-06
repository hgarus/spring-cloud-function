/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.function.serverless.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

public class ProxyHttpServletRequest implements HttpServletRequest {

	private static final String CHARSET_PREFIX = "charset=";

	private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

	private static final BufferedReader EMPTY_BUFFERED_READER = new BufferedReader(new StringReader(""));

	/**
	 * Date formats as specified in the HTTP RFC.
	 *
	 * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.1.1">Section
	 *      7.1.1.1 of RFC 7231</a>
	 */
	private static final String[] DATE_FORMATS = new String[] { "EEE, dd MMM yyyy HH:mm:ss zzz",
			"EEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM dd HH:mm:ss yyyy" };

	private final ServletContext servletContext;

	// ---------------------------------------------------------------------
	// ServletRequest properties
	// ---------------------------------------------------------------------

	private final Map<String, Object> attributes = new LinkedHashMap<>();

	@Nullable
	private String characterEncoding;

	@Nullable
	private byte[] content;

	@Nullable
	private String contentType;

	@Nullable
	private ServletInputStream inputStream;

	@Nullable
	private BufferedReader reader;

	private final Map<String, String[]> parameters = new LinkedHashMap<>(16);

	/** List of locales in descending order. */
	private final LinkedList<Locale> locales = new LinkedList<>();


	private boolean asyncStarted = false;

	private boolean asyncSupported = false;

	private DispatcherType dispatcherType = DispatcherType.REQUEST;

	// ---------------------------------------------------------------------
	// HttpServletRequest properties
	// ---------------------------------------------------------------------

	@Nullable
	private String authType;

	@Nullable
	private Cookie[] cookies;

	private final Map<String, HeaderValueHolder> headers = new LinkedCaseInsensitiveMap<>();

	@Nullable
	private String method;

	@Nullable
	private String pathInfo;

	private String contextPath = "";

	@Nullable
	private String queryString;

	@Nullable
	private String remoteUser;

	private final Set<String> userRoles = new HashSet<>();

	@Nullable
	private Principal userPrincipal;

	@Nullable
	private String requestedSessionId;

	@Nullable
	private String requestURI;

	private String servletPath = "";

	@Nullable
	private HttpSession session;

	private boolean requestedSessionIdValid = true;

	private boolean requestedSessionIdFromCookie = true;

	private boolean requestedSessionIdFromURL = false;

	private final MultiValueMap<String, Part> parts = new LinkedMultiValueMap<>();


	public ProxyHttpServletRequest(ServletContext servletContext, String method, String requestURI) {
		this.servletContext = servletContext;
		this.method = method;
		this.requestURI = requestURI;
		this.locales.add(Locale.ENGLISH);
	}

	/**
	 * Return the ServletContext that this request is associated with. (Not
	 * available in the standard HttpServletRequest interface for some reason.)
	 */
	@Override
	public ServletContext getServletContext() {
		return this.servletContext;
	}

	@Override
	public Object getAttribute(String name) {
		return this.attributes.get(name);
	}

	@Override
	public Enumeration<String> getAttributeNames() {
		return Collections.enumeration(new LinkedHashSet<>(this.attributes.keySet()));
	}

	@Override
	@Nullable
	public String getCharacterEncoding() {
		return this.characterEncoding;
	}

	@Override
	public void setCharacterEncoding(@Nullable String characterEncoding) {
		this.characterEncoding = characterEncoding;
		updateContentTypeHeader();
	}

	private void updateContentTypeHeader() {
		if (StringUtils.hasLength(this.contentType)) {
			String value = this.contentType;
			if (StringUtils.hasLength(this.characterEncoding)
					&& !this.contentType.toLowerCase().contains(CHARSET_PREFIX)) {
				value += ';' + CHARSET_PREFIX + this.characterEncoding;
			}
			doAddHeaderValue(HttpHeaders.CONTENT_TYPE, value, true);
		}
	}

	/**
	 * Set the content of the request body as a byte array.
	 * <p>
	 * If the supplied byte array represents text such as XML or JSON, the
	 * {@link #setCharacterEncoding character encoding} should typically be set as
	 * well.
	 *
	 * @see #setCharacterEncoding(String)
	 * @see #getContentAsByteArray()
	 * @see #getContentAsString()
	 */
	public void setContent(@Nullable byte[] content) {
		this.content = content;
		this.inputStream = null;
		this.reader = null;
	}

	/**
	 * Get the content of the request body as a byte array.
	 *
	 * @return the content as a byte array (potentially {@code null})
	 * @since 5.0
	 * @see #setContent(byte[])
	 * @see #getContentAsString()
	 */
	@Nullable
	public byte[] getContentAsByteArray() {
		return this.content;
	}

	/**
	 * Get the content of the request body as a {@code String}, using the configured
	 * {@linkplain #getCharacterEncoding character encoding}.
	 *
	 * @return the content as a {@code String}, potentially {@code null}
	 * @throws IllegalStateException        if the character encoding has not been
	 *                                      set
	 * @throws UnsupportedEncodingException if the character encoding is not
	 *                                      supported
	 * @since 5.0
	 * @see #setContent(byte[])
	 * @see #setCharacterEncoding(String)
	 * @see #getContentAsByteArray()
	 */
	@Nullable
	public String getContentAsString() throws IllegalStateException, UnsupportedEncodingException {
		Assert.state(this.characterEncoding != null, "Cannot get content as a String for a null character encoding. "
				+ "Consider setting the characterEncoding in the request.");

		if (this.content == null) {
			return null;
		}
		return new String(this.content, this.characterEncoding);
	}

	@Override
	public int getContentLength() {
		return (this.content != null ? this.content.length : -1);
	}

	@Override
	public long getContentLengthLong() {
		return getContentLength();
	}

	public void setContentType(@Nullable String contentType) {
		this.contentType = contentType;
		if (contentType != null) {
			try {
				MediaType mediaType = MediaType.parseMediaType(contentType);
				if (mediaType.getCharset() != null) {
					this.characterEncoding = mediaType.getCharset().name();
				}
			}
			catch (IllegalArgumentException ex) {
				// Try to get charset value anyway
				int charsetIndex = contentType.toLowerCase().indexOf(CHARSET_PREFIX);
				if (charsetIndex != -1) {
					this.characterEncoding = contentType.substring(charsetIndex + CHARSET_PREFIX.length());
				}
			}
			updateContentTypeHeader();
		}
	}

	@Override
	@Nullable
	public String getContentType() {
		return this.contentType;
	}

	@Override
	public ServletInputStream getInputStream() {
		InputStream stream = new ByteArrayInputStream(this.content);
		return new ServletInputStream() {

			boolean finished = false;

			@Override
			public int read() throws IOException {
		        int readByte = stream.read();
		        if (readByte == -1) {
		            finished = true;
		        }
		        return readByte;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}

			@Override
			public boolean isReady() {
				return !finished;
			}

			@Override
			public boolean isFinished() {
				return finished;
			}
		};
	}

	/**
	 * Set a single value for the specified HTTP parameter.
	 * <p>
	 * If there are already one or more values registered for the given parameter
	 * name, they will be replaced.
	 */
	public void setParameter(String name, String value) {
		setParameter(name, new String[] { value });
	}

	/**
	 * Set an array of values for the specified HTTP parameter.
	 * <p>
	 * If there are already one or more values registered for the given parameter
	 * name, they will be replaced.
	 */
	public void setParameter(String name, String... values) {
		Assert.notNull(name, "Parameter name must not be null");
		this.parameters.put(name, values);
	}

	/**
	 * Set all provided parameters <strong>replacing</strong> any existing values
	 * for the provided parameter names. To add without replacing existing values,
	 * use {@link #addParameters(java.util.Map)}.
	 */
	public void setParameters(Map<String, ?> params) {
		Assert.notNull(params, "Parameter map must not be null");
		params.forEach((key, value) -> {
			if (value instanceof String) {
				setParameter(key, (String) value);
			}
			else if (value instanceof String[]) {
				setParameter(key, (String[]) value);
			}
			else {
				throw new IllegalArgumentException("Parameter map value must be single value " + " or array of type ["
						+ String.class.getName() + "]");
			}
		});
	}

	/**
	 * Add a single value for the specified HTTP parameter.
	 * <p>
	 * If there are already one or more values registered for the given parameter
	 * name, the given value will be added to the end of the list.
	 */
	public void addParameter(String name, @Nullable String value) {
		addParameter(name, new String[] { value });
	}

	/**
	 * Add an array of values for the specified HTTP parameter.
	 * <p>
	 * If there are already one or more values registered for the given parameter
	 * name, the given values will be added to the end of the list.
	 */
	public void addParameter(String name, String... values) {
		Assert.notNull(name, "Parameter name must not be null");
		String[] oldArr = this.parameters.get(name);
		if (oldArr != null) {
			String[] newArr = new String[oldArr.length + values.length];
			System.arraycopy(oldArr, 0, newArr, 0, oldArr.length);
			System.arraycopy(values, 0, newArr, oldArr.length, values.length);
			this.parameters.put(name, newArr);
		}
		else {
			this.parameters.put(name, values);
		}
	}

	/**
	 * Add all provided parameters <strong>without</strong> replacing any existing
	 * values. To replace existing values, use
	 * {@link #setParameters(java.util.Map)}.
	 */
	public void addParameters(Map<String, ?> params) {
		Assert.notNull(params, "Parameter map must not be null");
		params.forEach((key, value) -> {
			if (value instanceof String) {
				addParameter(key, (String) value);
			}
			else if (value instanceof String[]) {
				addParameter(key, (String[]) value);
			}
			else {
				throw new IllegalArgumentException("Parameter map value must be single value " + " or array of type ["
						+ String.class.getName() + "]");
			}
		});
	}

	/**
	 * Remove already registered values for the specified HTTP parameter, if any.
	 */
	public void removeParameter(String name) {
		Assert.notNull(name, "Parameter name must not be null");
		this.parameters.remove(name);
	}

	/**
	 * Remove all existing parameters.
	 */
	public void removeAllParameters() {
		this.parameters.clear();
	}

	@Override
	@Nullable
	public String getParameter(String name) {
		Assert.notNull(name, "Parameter name must not be null");
		String[] arr = this.parameters.get(name);
		return (arr != null && arr.length > 0 ? arr[0] : null);
	}

	@Override
	public Enumeration<String> getParameterNames() {
		return Collections.enumeration(this.parameters.keySet());
	}

	@Override
	public String[] getParameterValues(String name) {
		Assert.notNull(name, "Parameter name must not be null");
		return this.parameters.get(name);
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		return Collections.unmodifiableMap(this.parameters);
	}

	@Override
	public String getProtocol() {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getScheme() {
		throw new UnsupportedOperationException();
	}

	public void setServerName(String serverName) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getServerName() {
		throw new UnsupportedOperationException();
	}

	public void setServerPort(int serverPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getServerPort() {
		throw new UnsupportedOperationException();
	}

	@Override
	public BufferedReader getReader() throws UnsupportedEncodingException {
		if (this.reader != null) {
			return this.reader;
		}
		else if (this.inputStream != null) {
			throw new IllegalStateException(
					"Cannot call getReader() after getInputStream() has already been called for the current request");
		}

		if (this.content != null) {
			InputStream sourceStream = new ByteArrayInputStream(this.content);
			Reader sourceReader = (this.characterEncoding != null)
					? new InputStreamReader(sourceStream, this.characterEncoding)
					: new InputStreamReader(sourceStream);
			this.reader = new BufferedReader(sourceReader);
		}
		else {
			this.reader = EMPTY_BUFFERED_READER;
		}
		return this.reader;
	}

	public void setRemoteAddr(String remoteAddr) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getRemoteAddr() {
		return "proxy";
	}

	public void setRemoteHost(String remoteHost) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getRemoteHost() {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setAttribute(String name, @Nullable Object value) {
		Assert.notNull(name, "Attribute name must not be null");
		if (value != null) {
			this.attributes.put(name, value);
		}
		else {
			this.attributes.remove(name);
		}
	}

	@Override
	public void removeAttribute(String name) {
		Assert.notNull(name, "Attribute name must not be null");
		this.attributes.remove(name);
	}

	/**
	 * Clear all of this request's attributes.
	 */
	public void clearAttributes() {
		this.attributes.clear();
	}

	/**
	 * Add a new preferred locale, before any existing locales.
	 *
	 * @see #setPreferredLocales
	 */
	public void addPreferredLocale(Locale locale) {
		Assert.notNull(locale, "Locale must not be null");
		this.locales.addFirst(locale);
		updateAcceptLanguageHeader();
	}

	/**
	 * Set the list of preferred locales, in descending order, effectively replacing
	 * any existing locales.
	 *
	 * @since 3.2
	 * @see #addPreferredLocale
	 */
	public void setPreferredLocales(List<Locale> locales) {
		Assert.notEmpty(locales, "Locale list must not be empty");
		this.locales.clear();
		this.locales.addAll(locales);
		updateAcceptLanguageHeader();
	}

	private void updateAcceptLanguageHeader() {
		HttpHeaders headers = new HttpHeaders();
		headers.setAcceptLanguageAsLocales(this.locales);
		doAddHeaderValue(HttpHeaders.ACCEPT_LANGUAGE, headers.getFirst(HttpHeaders.ACCEPT_LANGUAGE), true);
	}

	/**
	 * Return the first preferred {@linkplain Locale locale} configured in this mock
	 * request.
	 * <p>
	 * If no locales have been explicitly configured, the default, preferred
	 * {@link Locale} for the <em>server</em> mocked by this request is
	 * {@link Locale#ENGLISH}.
	 * <p>
	 * In contrast to the Servlet specification, this mock implementation does
	 * <strong>not</strong> take into consideration any locales specified via the
	 * {@code Accept-Language} header.
	 *
	 * @see javax.servlet.ServletRequest#getLocale()
	 * @see #addPreferredLocale(Locale)
	 * @see #setPreferredLocales(List)
	 */
	@Override
	public Locale getLocale() {
		return this.locales.getFirst();
	}

	/**
	 * Return an {@linkplain Enumeration enumeration} of the preferred
	 * {@linkplain Locale locales} configured in this mock request.
	 * <p>
	 * If no locales have been explicitly configured, the default, preferred
	 * {@link Locale} for the <em>server</em> mocked by this request is
	 * {@link Locale#ENGLISH}.
	 * <p>
	 * In contrast to the Servlet specification, this mock implementation does
	 * <strong>not</strong> take into consideration any locales specified via the
	 * {@code Accept-Language} header.
	 *
	 * @see javax.servlet.ServletRequest#getLocales()
	 * @see #addPreferredLocale(Locale)
	 * @see #setPreferredLocales(List)
	 */
	@Override
	public Enumeration<Locale> getLocales() {
		return Collections.enumeration(this.locales);
	}

	/**
	 * Return {@code true} if the {@link #setSecure secure} flag has been set to
	 * {@code true} or if the {@link #getScheme scheme} is {@code https}.
	 *
	 * @see javax.servlet.ServletRequest#isSecure()
	 */
	@Override
	public boolean isSecure() {
		throw new UnsupportedOperationException();
	}

	@Override
	public RequestDispatcher getRequestDispatcher(String path) {
		throw new UnsupportedOperationException();
	}

	@Override
	@Deprecated
	public String getRealPath(String path) {
		return this.servletContext.getRealPath(path);
	}

	public void setRemotePort(int remotePort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getRemotePort() {
		throw new UnsupportedOperationException();
	}

	public void setLocalName(String localName) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getLocalName() {
		throw new UnsupportedOperationException();
	}

	public void setLocalAddr(String localAddr) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getLocalAddr() {
		return "proxy";
	}

	public void setLocalPort(int localPort) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getLocalPort() {
		throw new UnsupportedOperationException();
	}

	@Override
	public AsyncContext startAsync() {
		return startAsync(this, null);
	}

	@Override
	public AsyncContext startAsync(ServletRequest request, @Nullable ServletResponse response) {
		throw new UnsupportedOperationException();
	}

	public void setAsyncStarted(boolean asyncStarted) {
		this.asyncStarted = asyncStarted;
	}

	@Override
	public boolean isAsyncStarted() {
		return this.asyncStarted;
	}

	public void setAsyncSupported(boolean asyncSupported) {
		this.asyncSupported = asyncSupported;
	}

	@Override
	public boolean isAsyncSupported() {
		return this.asyncSupported;
	}

	public void setAsyncContext(@Nullable AsyncContext asyncContext) {
		throw new UnsupportedOperationException();
	}

	@Override
	@Nullable
	public AsyncContext getAsyncContext() {
		return null;
	}

	public void setDispatcherType(DispatcherType dispatcherType) {
		this.dispatcherType = dispatcherType;
	}

	@Override
	public javax.servlet.DispatcherType getDispatcherType() {
		return this.dispatcherType;
	}

	public void setAuthType(@Nullable String authType) {
		this.authType = authType;
	}

	@Override
	@Nullable
	public String getAuthType() {
		return this.authType;
	}

	@Override
	@Nullable
	public Cookie[] getCookies() {
		return this.cookies;
	}

	/**
	 * Add an HTTP header entry for the given name.
	 * <p>
	 * While this method can take any {@code Object} as a parameter, it is
	 * recommended to use the following types:
	 * <ul>
	 * <li>String or any Object to be converted using {@code toString()}; see
	 * {@link #getHeader}.</li>
	 * <li>String, Number, or Date for date headers; see
	 * {@link #getDateHeader}.</li>
	 * <li>String or Number for integer headers; see {@link #getIntHeader}.</li>
	 * <li>{@code String[]} or {@code Collection<String>} for multiple values; see
	 * {@link #getHeaders}.</li>
	 * </ul>
	 *
	 * @see #getHeaderNames
	 * @see #getHeaders
	 * @see #getHeader
	 * @see #getDateHeader
	 */
	public void addHeader(String name, Object value) {
		if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name) && !this.headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
			setContentType(value.toString());
		}
		else if (HttpHeaders.ACCEPT_LANGUAGE.equalsIgnoreCase(name)
				&& !this.headers.containsKey(HttpHeaders.ACCEPT_LANGUAGE)) {
			try {
				HttpHeaders headers = new HttpHeaders();
				headers.add(HttpHeaders.ACCEPT_LANGUAGE, value.toString());
				List<Locale> locales = headers.getAcceptLanguageAsLocales();
				this.locales.clear();
				this.locales.addAll(locales);
				if (this.locales.isEmpty()) {
					this.locales.add(Locale.ENGLISH);
				}
			}
			catch (IllegalArgumentException ex) {
				// Invalid Accept-Language format -> just store plain header
			}
			doAddHeaderValue(name, value, true);
		}
		else {
			doAddHeaderValue(name, value, false);
		}
	}

	private void doAddHeaderValue(String name, @Nullable Object value, boolean replace) {
		HeaderValueHolder header = this.headers.get(name);
		Assert.notNull(value, "Header value must not be null");
		if (header == null || replace) {
			header = new HeaderValueHolder();
			this.headers.put(name, header);
		}
		if (value instanceof Collection) {
			header.addValues((Collection<?>) value);
		}
		else if (value.getClass().isArray()) {
			header.addValueArray(value);
		}
		else {
			header.addValue(value);
		}
	}

	/**
	 * Return the long timestamp for the date header with the given {@code name}.
	 * <p>
	 * If the internal value representation is a String, this method will try to
	 * parse it as a date using the supported date formats:
	 * <ul>
	 * <li>"EEE, dd MMM yyyy HH:mm:ss zzz"</li>
	 * <li>"EEE, dd-MMM-yy HH:mm:ss zzz"</li>
	 * <li>"EEE MMM dd HH:mm:ss yyyy"</li>
	 * </ul>
	 *
	 * @param name the header name
	 * @see <a href="https://tools.ietf.org/html/rfc7231#section-7.1.1.1">Section
	 *      7.1.1.1 of RFC 7231</a>
	 */
	@Override
	public long getDateHeader(String name) {
		HeaderValueHolder header = this.headers.get(name);
		Object value = (header != null ? header.getValue() : null);
		if (value instanceof Date) {
			return ((Date) value).getTime();
		}
		else if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		else if (value instanceof String) {
			return parseDateHeader(name, (String) value);
		}
		else if (value != null) {
			throw new IllegalArgumentException(
					"Value for header '" + name + "' is not a Date, Number, or String: " + value);
		}
		else {
			return -1L;
		}
	}

	private long parseDateHeader(String name, String value) {
		for (String dateFormat : DATE_FORMATS) {
			SimpleDateFormat simpleDateFormat = new SimpleDateFormat(dateFormat, Locale.US);
			simpleDateFormat.setTimeZone(GMT);
			try {
				return simpleDateFormat.parse(value).getTime();
			}
			catch (ParseException ex) {
				// ignore
			}
		}
		throw new IllegalArgumentException("Cannot parse date value '" + value + "' for '" + name + "' header");
	}

	@Override
	@Nullable
	public String getHeader(String name) {
		HeaderValueHolder header = this.headers.get(name);
		return (header != null ? header.getStringValue() : null);
	}

	@Override
	public Enumeration<String> getHeaders(String name) {
		HeaderValueHolder header = this.headers.get(name);
		return Collections.enumeration(header != null ? header.getStringValues() : new LinkedList<>());
	}

	@Override
	public Enumeration<String> getHeaderNames() {
		return Collections.enumeration(this.headers.keySet());
	}

	@Override
	public int getIntHeader(String name) {
		HeaderValueHolder header = this.headers.get(name);
		Object value = (header != null ? header.getValue() : null);
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		else if (value instanceof String) {
			return Integer.parseInt((String) value);
		}
		else if (value != null) {
			throw new NumberFormatException("Value for header '" + name + "' is not a Number: " + value);
		}
		else {
			return -1;
		}
	}

	public void setMethod(@Nullable String method) {
		this.method = method;
	}

	@Override
	@Nullable
	public String getMethod() {
		return this.method;
	}

	public void setPathInfo(@Nullable String pathInfo) {
		this.pathInfo = pathInfo;
	}

	@Override
	@Nullable
	public String getPathInfo() {
		return this.pathInfo;
	}

	@Override
	@Nullable
	public String getPathTranslated() {
		return (this.pathInfo != null ? getRealPath(this.pathInfo) : null);
	}

	public void setContextPath(String contextPath) {
		this.contextPath = contextPath;
	}

	@Override
	public String getContextPath() {
		return this.contextPath;
	}

	public void setQueryString(@Nullable String queryString) {
		this.queryString = queryString;
	}

	@Override
	@Nullable
	public String getQueryString() {
		return this.queryString;
	}

	public void setRemoteUser(@Nullable String remoteUser) {
		this.remoteUser = remoteUser;
	}

	@Override
	@Nullable
	public String getRemoteUser() {
		return this.remoteUser;
	}

	public void addUserRole(String role) {
		this.userRoles.add(role);
	}

	@Override
	public boolean isUserInRole(String role) {
		throw new UnsupportedOperationException();
	}

	public void setUserPrincipal(@Nullable Principal userPrincipal) {
		this.userPrincipal = userPrincipal;
	}

	@Override
	@Nullable
	public Principal getUserPrincipal() {
		return this.userPrincipal;
	}

	public void setRequestedSessionId(@Nullable String requestedSessionId) {
		this.requestedSessionId = requestedSessionId;
	}

	@Override
	@Nullable
	public String getRequestedSessionId() {
		return this.requestedSessionId;
	}

	public void setRequestURI(@Nullable String requestURI) {
		this.requestURI = requestURI;
	}

	@Override
	@Nullable
	public String getRequestURI() {
		return this.requestURI;
	}

	@Override
	public StringBuffer getRequestURL() {
		throw new UnsupportedOperationException();
	}

	public void setServletPath(String servletPath) {
		this.servletPath = servletPath;
	}

	@Override
	public String getServletPath() {
		return this.servletPath;
	}

	public void setSession(HttpSession session) {
		throw new UnsupportedOperationException();
	}

	@Override
	@Nullable
	public HttpSession getSession(boolean create) {
		return this.session;
	}

	@Override
	@Nullable
	public HttpSession getSession() {
		return getSession(true);
	}

	@Override
	public String changeSessionId() {
		throw new UnsupportedOperationException();
	}

	public void setRequestedSessionIdValid(boolean requestedSessionIdValid) {
		this.requestedSessionIdValid = requestedSessionIdValid;
	}

	@Override
	public boolean isRequestedSessionIdValid() {
		return this.requestedSessionIdValid;
	}

	public void setRequestedSessionIdFromCookie(boolean requestedSessionIdFromCookie) {
		this.requestedSessionIdFromCookie = requestedSessionIdFromCookie;
	}

	@Override
	public boolean isRequestedSessionIdFromCookie() {
		return this.requestedSessionIdFromCookie;
	}

	public void setRequestedSessionIdFromURL(boolean requestedSessionIdFromURL) {
		this.requestedSessionIdFromURL = requestedSessionIdFromURL;
	}

	@Override
	public boolean isRequestedSessionIdFromURL() {
		return this.requestedSessionIdFromURL;
	}

	@Override
	@Deprecated
	public boolean isRequestedSessionIdFromUrl() {
		return isRequestedSessionIdFromURL();
	}

	@Override
	public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void login(String username, String password) throws ServletException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void logout() throws ServletException {
		this.userPrincipal = null;
		this.remoteUser = null;
		this.authType = null;
	}

	public void addPart(Part part) {
		this.parts.add(part.getName(), part);
	}

	@Override
	@Nullable
	public Part getPart(String name) throws IOException, ServletException {
		return this.parts.getFirst(name);
	}

	@Override
	public Collection<javax.servlet.http.Part> getParts() throws IOException, ServletException {
		List<Part> result = new LinkedList<>();
		for (List<Part> list : this.parts.values()) {
			result.addAll(list);
		}
		return result;
	}

	@Override
	public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
		throw new UnsupportedOperationException();
	}
}
