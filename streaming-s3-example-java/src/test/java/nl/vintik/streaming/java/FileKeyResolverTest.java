package nl.vintik.streaming.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the {@link RequestResolver} abstraction and its API Gateway implementation
 * {@link FileKeyResolver} (Req 3.4).
 *
 * <p>{@link FileKeyResolver} is driven through its package-private test-seam constructor with a
 * Mockito {@link RequestParser} and {@link FileNameValidator}, so each parse/validation outcome is
 * exercised in isolation without constructing a real proxy-event wire shape (that path is covered by
 * {@link RequestParserTest} and {@link StreamHandlerTest}). Assertions are on the typed
 * {@link RequestResult} — {@link RequestResult.Resolved} vs {@link RequestResult.Error} with its
 * status code — never on whole-response string equality.
 *
 * <p>The three Req-3.4 cases are covered:
 * <ul>
 *   <li>parse failure &rarr; {@link RequestResult.Error} with status 400;</li>
 *   <li>validation rejection &rarr; {@link RequestResult.Error} 400, and (since the resolver has no
 *       source collaborator) no downstream work beyond validation — the validator is consulted and
 *       a typed {@code Error} is returned, which is what the handler keys its no-S3 decision on;</li>
 *   <li>a valid name &rarr; {@link RequestResult.Resolved} carrying the {@link FileRequest}.</li>
 * </ul>
 *
 * <p>Requirements: 3.4
 */
@ExtendWith(MockitoExtension.class)
// assertInstanceOf(RequestResult.Error.class, ...) returns the raw record type; binding it to the
// RequestResult.Error<FileRequest> local is an unchecked conversion with no runtime risk here.
@SuppressWarnings("unchecked")
class FileKeyResolverTest {

    private static final String ACCEPTED_FILE = "report.bin";

    @Mock
    private RequestParser parser;

    @Mock
    private FileNameValidator validator;

    private FileKeyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FileKeyResolver(parser, validator);
    }

    private static InputStream input() {
        return new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8));
    }

    // ---- parse failure -> 400 ---------------------------------------------------------------

    @Test
    @DisplayName("Given the parser reports a ParseError When resolved Then it yields Error 400 and never validates")
    void parseFailureYields400() {
        when(parser.parse(any())).thenReturn(new ParseResult.ParseError());

        RequestResult<FileRequest> result = resolver.resolve(input());

        RequestResult.Error<FileRequest> error =
                assertInstanceOf(RequestResult.Error.class, result, "a parse failure must map to an Error");
        assertEquals(400, error.statusCode(), "an unparseable event must map to HTTP 400");
        // A parse failure short-circuits before validation: no file name is ever validated.
        verifyNoInteractions(validator);
    }

    // ---- validation rejection -> 400, no S3 / downstream request ----------------------------

    @ParameterizedTest(name = "[{index}] reason={0}")
    @EnumSource(ValidationResult.Reason.class)
    @DisplayName("Given validation rejects the name When resolved Then it yields Error 400 and issues no source request")
    void validationRejectionYields400(ValidationResult.Reason reason) {
        // Parser succeeds with some non-empty name; the validator is the one that rejects it.
        when(parser.parse(any())).thenReturn(new ParseResult.Parsed(new StreamRequest("whatever")));
        when(validator.validate(anyString())).thenReturn(new ValidationResult.Invalid(reason));

        RequestResult<FileRequest> result = resolver.resolve(input());

        RequestResult.Error<FileRequest> error =
                assertInstanceOf(RequestResult.Error.class, result, "a rejected name must map to an Error");
        assertEquals(400, error.statusCode(), "every validation rejection must map to HTTP 400");
        // The resolver has no source collaborator; a rejection produces a typed Error and nothing
        // more. The handler keys its "no S3 request" decision on exactly this Error outcome, so the
        // typed result is the no-source guarantee at this layer.
        verify(validator).validate(anyString());
    }

    @Test
    @DisplayName("Given a missing name When resolved Then it yields the missing-name 400 message")
    void missingNameHasDistinctMessage() {
        when(parser.parse(any())).thenReturn(new ParseResult.Parsed(new StreamRequest("")));
        when(validator.validate(anyString())).thenReturn(new ValidationResult.Invalid(ValidationResult.Reason.MISSING));

        RequestResult<FileRequest> result = resolver.resolve(input());

        RequestResult.Error<FileRequest> error = assertInstanceOf(RequestResult.Error.class, result);
        assertEquals(400, error.statusCode());
        assertEquals("The file name is missing.", error.message());
    }

    @Test
    @DisplayName("Given a path-safety rejection When resolved Then the 400 message does not disclose the specific rule")
    void pathSafetyRejectionUsesGenericMessage() {
        when(parser.parse(any())).thenReturn(new ParseResult.Parsed(new StreamRequest("../etc")));
        when(validator.validate(anyString()))
                .thenReturn(new ValidationResult.Invalid(ValidationResult.Reason.PARENT_DIR));

        RequestResult<FileRequest> result = resolver.resolve(input());

        RequestResult.Error<FileRequest> error = assertInstanceOf(RequestResult.Error.class, result);
        assertEquals(400, error.statusCode());
        assertEquals("The file name was rejected.", error.message());
    }

    // ---- valid name -> Resolved(FileRequest(name)) ------------------------------------------

    @Test
    @DisplayName("Given a valid name When resolved Then it yields Resolved(FileRequest(name))")
    void validNameYieldsResolved() {
        when(parser.parse(any())).thenReturn(new ParseResult.Parsed(new StreamRequest(ACCEPTED_FILE)));
        when(validator.validate(anyString())).thenReturn(new ValidationResult.Valid(ACCEPTED_FILE));

        RequestResult<FileRequest> result = resolver.resolve(input());

        RequestResult.Resolved<FileRequest> resolved =
                assertInstanceOf(RequestResult.Resolved.class, result, "a valid name must map to Resolved");
        assertEquals(new FileRequest(ACCEPTED_FILE), resolved.request());
    }

    // ---- RequestResolver abstraction ---------------------------------------------------------

    @Test
    @DisplayName("Given a RequestResolver lambda When invoked Then the functional interface returns its typed result")
    void requestResolverIsAFunctionalInterfaceOverTheInputStream() {
        FileRequest expected = new FileRequest("abc.txt");
        // RequestResolver<R> is a @FunctionalInterface: an implementation maps an InputStream to a
        // typed RequestResult<R>. This pins the abstraction's contract independent of FileKeyResolver.
        RequestResolver<FileRequest> resolved = in -> new RequestResult.Resolved<>(expected);
        RequestResolver<FileRequest> rejected = in -> new RequestResult.Error<>(400, "nope");

        RequestResult.Resolved<FileRequest> ok =
                assertInstanceOf(RequestResult.Resolved.class, resolved.resolve(input()));
        assertSame(expected, ok.request());

        RequestResult.Error<FileRequest> err =
                assertInstanceOf(RequestResult.Error.class, rejected.resolve(input()));
        assertEquals(400, err.statusCode());
    }
}
