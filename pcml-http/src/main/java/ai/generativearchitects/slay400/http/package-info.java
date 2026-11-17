/**
 * The HTTP binding of an API, without a web framework, a server or JT400: routes ({@link ai.generativearchitects.slay400.http.RouteTable}),
 * reading a request into the values that {@code OperationCodec.encode} takes and writing the JSON response
 * ({@link ai.generativearchitects.slay400.http.HttpBinding}), and RFC 9457 problem details
 * ({@link ai.generativearchitects.slay400.http.ProblemDetails}). It works on plain values: strings, maps and bytes in, values
 * and bytes out. A gateway's HTTP layer is an adapter around it, and the OpenAPI document of the API describes the same
 * contract, which the tests of this module hold together with it.
 */
package ai.generativearchitects.slay400.http;
