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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.LinkedCaseInsensitiveMap;
import org.springframework.web.util.WebUtils;

public class ProxyHttpServletResponse implements HttpServletResponse {

	private static final String CHARSET_PREFIX = "charset=";

	private static final String DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";

	private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

	// ---------------------------------------------------------------------
	// ServletResponse properties
	// ---------------------------------------------------------------------

	private boolean outputStreamAccessAllowed = true;

	private String defaultCharacterEncoding = WebUtils.DEFAULT_CHARACTER_ENCODING;

	private String characterEncoding = this.defaultCharacterEncoding;

	/**
	 * {@code true} if the character encoding has been explicitly set through
	 * {@link HttpServletResponse} methods or through a {@code charset} parameter on
	 * the {@code Content-Type}.
	 */
	private boolean characterEncodingSet = false;

	private final ByteArrayOutputStream content = new ByteArrayOutputStream(1024);

	private final ServletOutputStream outputStream = new ResponseServletOutputStream();

	private long contentLength = 0;

	private String contentType;

	private int bufferSize = 4096;

	private boolean committed;

	private Locale locale = Locale.getDefault();

	// ---------------------------------------------------------------------
	// HttpServletResponse properties
	// ---------------------------------------------------------------------

	private final List<Cookie> cookies = new ArrayList<>();

	private final Map<String, HeaderValueHolder> headers = new LinkedCaseInsensitiveMap<>();

	private int status = HttpServletResponse.SC_OK;

	@Nullable
	private String errorMessage;

	// ---------------------------------------------------------------------
	// ServletResponse interface
	// ---------------------------------------------------------------------

	@Override
	public void setCharacterEncoding(String characterEncoding) {
		setExplicitCharacterEncoding(characterEncoding);
		updateContentTypePropertyAndHeader();
	}

	private void setExplicitCharacterEncoding(String characterEncoding) {
		Assert.notNull(characterEncoding, "'characterEncoding' must not be null");
		this.characterEncoding = characterEncoding;
		this.characterEncodingSet = true;
	}

	private void updateContentTypePropertyAndHeader() {
		if (this.contentType != null) {
			String value = this.contentType;
			if (this.characterEncodingSet && !value.toLowerCase().contains(CHARSET_PREFIX)) {
				value += ';' + CHARSET_PREFIX + getCharacterEncoding();
				this.contentType = value;
			}
			doAddHeaderValue(HttpHeaders.CONTENT_TYPE, value, true);
		}
	}

	@Override
	public String getCharacterEncoding() {
		return this.characterEncoding;
	}

	@Override
	public ServletOutputStream getOutputStream() {
		Assert.state(this.outputStreamAccessAllowed, "OutputStream access not allowed");
		return this.outputStream;
	}

	@Override
	public PrintWriter getWriter() throws UnsupportedEncodingException {
		throw new UnsupportedOperationException();
	}

	public byte[] getContentAsByteArray() {
		return this.content.toByteArray();
	}

	/**
	 * Get the content of the response body as a {@code String}, using the charset
	 * specified for the response by the application, either through
	 * {@link HttpServletResponse} methods or through a charset parameter on the
	 * {@code Content-Type}. If no charset has been explicitly defined, the
	 * {@linkplain #setDefaultCharacterEncoding(String) default character encoding}
	 * will be used.
	 *
	 * @return the content as a {@code String}
	 * @throws UnsupportedEncodingException if the character encoding is not
	 *                                      supported
	 * @see #getContentAsString(Charset)
	 * @see #setCharacterEncoding(String)
	 * @see #setContentType(String)
	 */
	public String getContentAsString() throws UnsupportedEncodingException {
		return this.content.toString(getCharacterEncoding());
	}

	/**
	 * Get the content of the response body as a {@code String}, using the provided
	 * {@code fallbackCharset} if no charset has been explicitly defined and
	 * otherwise using the charset specified for the response by the application,
	 * either through {@link HttpServletResponse} methods or through a charset
	 * parameter on the {@code Content-Type}.
	 *
	 * @return the content as a {@code String}
	 * @throws UnsupportedEncodingException if the character encoding is not
	 *                                      supported
	 * @since 5.2
	 * @see #getContentAsString()
	 * @see #setCharacterEncoding(String)
	 * @see #setContentType(String)
	 */
	public String getContentAsString(Charset fallbackCharset) throws UnsupportedEncodingException {
		String charsetName = (this.characterEncodingSet ? getCharacterEncoding() : fallbackCharset.name());
		return this.content.toString(charsetName);
	}

	@Override
	public void setContentLength(int contentLength) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setContentLengthLong(long len) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setContentType(@Nullable String contentType) {
		this.contentType = contentType;
	}

	@Override
	@Nullable
	public String getContentType() {
		return this.contentType;
	}

	@Override
	public void setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	@Override
	public int getBufferSize() {
		return this.bufferSize;
	}

	@Override
	public void flushBuffer() {

	}

	@Override
	public void resetBuffer() {
		Assert.state(!isCommitted(), "Cannot reset buffer - response is already committed");
		this.content.reset();
	}

	public void setCommitted(boolean committed) {
		this.committed = committed;
	}

	@Override
	public boolean isCommitted() {
		return this.committed;
	}

	@Override
	public void reset() {
		resetBuffer();
		this.characterEncoding = this.defaultCharacterEncoding;
		this.characterEncodingSet = false;
		this.contentLength = 0;
		this.contentType = null;
		this.locale = Locale.getDefault();
		this.cookies.clear();
		this.headers.clear();
		this.status = HttpServletResponse.SC_OK;
		this.errorMessage = null;
	}

	@Override
	public void setLocale(@Nullable Locale locale) {
		// Although the Javadoc for javax.servlet.ServletResponse.setLocale(Locale) does
		// not
		// state how a null value for the supplied Locale should be handled, both Tomcat
		// and
		// Jetty simply ignore a null value. So we do the same here.
		if (locale == null) {
			return;
		}
		this.locale = locale;
		doAddHeaderValue(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag(), true);
	}

	@Override
	public Locale getLocale() {
		return this.locale;
	}

	// ---------------------------------------------------------------------
	// HttpServletResponse interface
	// ---------------------------------------------------------------------

	@Override
	public void addCookie(Cookie cookie) {
		throw new UnsupportedOperationException();
	}

	@Nullable
	public Cookie getCookie(String name) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean containsHeader(String name) {
		return this.headers.containsKey(name);
	}

	/**
	 * Return the names of all specified headers as a Set of Strings.
	 * <p>
	 * As of Servlet 3.0, this method is also defined in
	 * {@link HttpServletResponse}.
	 *
	 * @return the {@code Set} of header name {@code Strings}, or an empty
	 *         {@code Set} if none
	 */
	@Override
	public Collection<String> getHeaderNames() {
		return this.headers.keySet();
	}

	/**
	 * Return the primary value for the given header as a String, if any. Will
	 * return the first value in case of multiple values.
	 * <p>
	 * As of Servlet 3.0, this method is also defined in
	 * {@link HttpServletResponse}. As of Spring 3.1, it returns a stringified value
	 * for Servlet 3.0 compatibility. Consider using {@link #getHeaderValue(String)}
	 * for raw Object access.
	 *
	 * @param name the name of the header
	 * @return the associated header value, or {@code null} if none
	 */
	@Override
	@Nullable
	public String getHeader(String name) {
		HeaderValueHolder header = this.headers.get(name);
		return (header != null ? header.getStringValue() : null);
	}

	/**
	 * Return all values for the given header as a List of Strings.
	 * <p>
	 * As of Servlet 3.0, this method is also defined in
	 * {@link HttpServletResponse}. As of Spring 3.1, it returns a List of
	 * stringified values for Servlet 3.0 compatibility. Consider using
	 * {@link #getHeaderValues(String)} for raw Object access.
	 *
	 * @param name the name of the header
	 * @return the associated header values, or an empty List if none
	 */
	@Override
	public List<String> getHeaders(String name) {
		HeaderValueHolder header = this.headers.get(name);
		if (header != null) {
			return header.getStringValues();
		}
		else {
			return Collections.emptyList();
		}
	}

	/**
	 * Return the primary value for the given header, if any.
	 * <p>
	 * Will return the first value in case of multiple values.
	 *
	 * @param name the name of the header
	 * @return the associated header value, or {@code null} if none
	 */
	@Nullable
	public Object getHeaderValue(String name) {
		HeaderValueHolder header = this.headers.get(name);
		return (header != null ? header.getValue() : null);
	}

	/**
	 * Return all values for the given header as a List of value objects.
	 *
	 * @param name the name of the header
	 * @return the associated header values, or an empty List if none
	 */
	public List<Object> getHeaderValues(String name) {
		HeaderValueHolder header = this.headers.get(name);
		if (header != null) {
			return header.getValues();
		}
		else {
			return Collections.emptyList();
		}
	}

	/**
	 * The default implementation returns the given URL String as-is.
	 * <p>
	 * Can be overridden in subclasses, appending a session id or the like.
	 */
	@Override
	public String encodeURL(String url) {
		return url;
	}

	/**
	 * The default implementation delegates to {@link #encodeURL}, returning the
	 * given URL String as-is.
	 * <p>
	 * Can be overridden in subclasses, appending a session id or the like in a
	 * redirect-specific fashion. For general URL encoding rules, override the
	 * common {@link #encodeURL} method instead, applying to redirect URLs as well
	 * as to general URLs.
	 */
	@Override
	public String encodeRedirectURL(String url) {
		return encodeURL(url);
	}

	@Override
	@Deprecated
	public String encodeUrl(String url) {
		return encodeURL(url);
	}

	@Override
	@Deprecated
	public String encodeRedirectUrl(String url) {
		return encodeRedirectURL(url);
	}

	@Override
	public void sendError(int status, String errorMessage) throws IOException {
		Assert.state(!isCommitted(), "Cannot set error status - response is already committed");
		this.status = status;
		this.errorMessage = errorMessage;
		setCommitted(true);
	}

	@Override
	public void sendError(int status) throws IOException {
		Assert.state(!isCommitted(), "Cannot set error status - response is already committed");
		this.status = status;
		setCommitted(true);
	}

	@Override
	public void sendRedirect(String url) throws IOException {
		Assert.state(!isCommitted(), "Cannot send redirect - response is already committed");
		Assert.notNull(url, "Redirect URL must not be null");
		setHeader(HttpHeaders.LOCATION, url);
		setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
		setCommitted(true);
	}

	@Nullable
	public String getRedirectedUrl() {
		return getHeader(HttpHeaders.LOCATION);
	}

	@Override
	public void setDateHeader(String name, long value) {
		setHeaderValue(name, formatDate(value));
	}

	@Override
	public void addDateHeader(String name, long value) {
		addHeaderValue(name, formatDate(value));
	}

	public long getDateHeader(String name) {
		String headerValue = getHeader(name);
		if (headerValue == null) {
			return -1;
		}
		try {
			return newDateFormat().parse(getHeader(name)).getTime();
		}
		catch (ParseException ex) {
			throw new IllegalArgumentException("Value for header '" + name + "' is not a valid Date: " + headerValue);
		}
	}

	private String formatDate(long date) {
		return newDateFormat().format(new Date(date));
	}

	private DateFormat newDateFormat() {
		SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT, Locale.US);
		dateFormat.setTimeZone(GMT);
		return dateFormat;
	}

	@Override
	public void setHeader(String name, @Nullable String value) {
		setHeaderValue(name, value);
	}

	@Override
	public void addHeader(String name, @Nullable String value) {
		addHeaderValue(name, value);
	}

	@Override
	public void setIntHeader(String name, int value) {
		setHeaderValue(name, value);
	}

	@Override
	public void addIntHeader(String name, int value) {
		addHeaderValue(name, value);
	}

	private void setHeaderValue(String name, @Nullable Object value) {
		if (value == null) {
			return;
		}
		boolean replaceHeader = true;
		doAddHeaderValue(name, value, replaceHeader);
	}

	private void addHeaderValue(String name, @Nullable Object value) {
		if (value == null) {
			return;
		}
		boolean replaceHeader = false;
		doAddHeaderValue(name, value, replaceHeader);
	}

	private void doAddHeaderValue(String name, Object value, boolean replace) {
		Assert.notNull(value, "Header value must not be null");
		HeaderValueHolder header = this.headers.computeIfAbsent(name, key -> new HeaderValueHolder());
		if (replace) {
			header.setValue(value);
		}
		else {
			header.addValue(value);
		}
	}

	@Override
	public void setStatus(int status) {
		if (!this.isCommitted()) {
			this.status = status;
		}
	}

	@Override
	@Deprecated
	public void setStatus(int status, String errorMessage) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getStatus() {
		return this.status;
	}

	@Nullable
	public String getErrorMessage() {
		return this.errorMessage;
	}

	// ---------------------------------------------------------------------
	// Methods for MockRequestDispatcher
	// ---------------------------------------------------------------------

	@Nullable
	public String getForwardedUrl() {
		throw new UnsupportedOperationException();
	}

	@Nullable
	public String getIncludedUrl() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Inner class that adapts the ServletOutputStream to mark the response as
	 * committed once the buffer size is exceeded.
	 */
	private class ResponseServletOutputStream extends ServletOutputStream {

		private WriteListener listener;

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setWriteListener(WriteListener writeListener) {
			if (writeListener != null) {
				try {
					writeListener.onWritePossible();
				}
				catch (IOException e) {
					// log.error("Output stream is not writable", e);
				}

				listener = writeListener;
			}
		}

		@Override
		public void write(int b) throws IOException {
			try {
				content.write(b);
			}
			catch (Exception e) {
				if (listener != null) {
					listener.onError(e);
				}
			}
		}

		@Override
		public void close() throws IOException {
			super.close();
			flushBuffer();
		}
	}

}
