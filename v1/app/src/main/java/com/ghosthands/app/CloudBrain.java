package com.ghosthands.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.29: the Fireworks cloud brain — an OPT-IN alternative
 * planner engine, Ryan's Oct 31, 2026 direction. Off by
 * default: the Settings brain choice (Settings.KEY_BRAIN)
 * defaults to the on-device Qwen, and a run carries one of
 * these only when the user has chosen Fireworks AND set an
 * API key on the device. With no CloudBrain in the Agent,
 * every run is the on-device behaviour byte-for-byte.
 *
 * THE SWAP IS A BRAIN SWAP, NEVER A GOVERNANCE BYPASS. The
 * Agent hands this class the very prompt the local engine
 * would have received (the same assembled contract), takes
 * back planner text, and runs it through the same
 * PlannerParse, the same guards, the same Governor and the
 * same trail machinery. The only things that differ are
 * where the text is computed and the model id the trail
 * records (trailModelId — deliberately distinct from every
 * ModelStore id, so a cloud run can never be misread as an
 * on-device one).
 *
 * THE KEY. The API key is entered by the user on the device
 * (Settings > BRAIN) and stored in the app's private
 * preferences under PREF_API_KEY — a key this class owns,
 * deliberately NOT a Settings key: the Settings model's map
 * is captured into run markers and round-tripped through
 * places a credential must never ride. No key exists in
 * source, in tests, or in any shipped artifact; this class
 * is inert without one (generate() refuses before any
 * network call when the key is absent).
 *
 * THE HONEST LABEL. While the cloud brain is selected,
 * every step's prompt — the goal, the page's text, its
 * element map, the run's history — leaves the phone for
 * Fireworks' API. The Settings copy says so where the
 * choice is made (the voice loop's cloud caveat is the
 * wording model), the run's feed says so when a cloud run
 * starts, and statusLine below is the one wording both
 * surfaces share.
 *
 * FAILURE DEGRADES THE ENGINE'S OWN WAY. The local engine
 * reports failure as an "ERROR: ..." string and the loop
 * stops the run on it immediately and honestly (v1.8: an
 * engine failure is not planner output). This class speaks
 * exactly that shape: a transport failure, a timeout, a
 * non-200 HTTP status, or a reply that does not carry a
 * message all come back as one "ERROR: ..." string after
 * exactly ONE attempt — never a retry loop, never a
 * fabricated action, never a silent fall back to the
 * on-device brain mid-run (the run's trail names one
 * brain; a quiet swap would falsify it). The loop records
 * the decide with the cloud model id and stops "error",
 * as it does for any engine failure.
 *
 * Pure: no Android. The HTTP call rides a Transport seam —
 * HttpTransport (HttpURLConnection) in production, a fake
 * in the host harness, which therefore never touches the
 * network.
 */
public class CloudBrain {

    /** The Fireworks inference endpoint (OpenAI-compatible
     *  chat completions). */
    public static final String API_URL =
            "https://api.fireworks.ai/inference/v1/chat/completions";

    /** The default Fireworks model: a small instruct model
     *  chosen for fast instruction following — the planner's
     *  whole job is one JSON action per prompt. A DEFAULT,
     *  not a promise of availability: if Fireworks does not
     *  serve it to the key's account, the API's error comes
     *  back in the open (see the class doc) and the run
     *  stops honestly. */
    public static final String DEFAULT_MODEL =
            "accounts/fireworks/models/llama-v3p1-8b-instruct";

    /** The trail prefix: a cloud run's records carry
     *  "fireworks:" + the model id — visibly not a
     *  ModelStore id (q4_k_m / q5_k_m / qwen3b). */
    public static final String TRAIL_PREFIX = "fireworks:";

    /** The SharedPreferences key the API key lives under,
     *  in the app's private preferences file. Owned here,
     *  NOT in Settings (see the class doc): the Settings
     *  map is captured into run markers; a credential must
     *  never ride it. */
    public static final String PREF_API_KEY = "fireworks_api_key";

    /** Network bounds for one decide. A cloud step should
     *  answer in seconds; the read bound is generous next
     *  to that and still far inside a local step's worst
     *  case, so a hung socket ends the run honestly instead
     *  of stranding it. */
    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 60000;

    /** One HTTP POST, abstracted so the harness can fake
     *  the wire. Returns the status and the raw body
     *  (the error body on a non-200, read by the caller
     *  for the honest error line). */
    public interface Transport {
        Response post(String url, String authorization,
                      String jsonBody) throws Exception;
    }

    /** A transport's answer: the HTTP status and the body
     *  text ("" when there was none). */
    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = (body == null) ? "" : body;
        }
    }

    /** The production transport: one HttpURLConnection
     *  POST of the JSON body with the bearer header, the
     *  class's timeout bounds, no redirects followed
     *  blindly (a redirect on an API POST is an error to
     *  report, not a road to wander). */
    public static final class HttpTransport implements Transport {
        @Override public Response post(String url,
                String authorization, String jsonBody)
                throws Exception {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)
                        new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Content-Type",
                        "application/json");
                conn.setRequestProperty("Accept",
                        "application/json");
                if (authorization != null) {
                    conn.setRequestProperty("Authorization",
                            authorization);
                }
                byte[] payload = jsonBody.getBytes("UTF-8");
                conn.setRequestProperty("Content-Length",
                        String.valueOf(payload.length));
                OutputStream out = conn.getOutputStream();
                out.write(payload);
                out.flush();
                out.close();
                int status = conn.getResponseCode();
                InputStream in = (status >= 200 && status < 300)
                        ? conn.getInputStream()
                        : conn.getErrorStream();
                return new Response(status, readAll(in));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private static String readAll(InputStream in)
                throws Exception {
            if (in == null) return "";
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            br.close();
            return sb.toString();
        }
    }

    private static final ModelEngine.Stats ZERO =
            new ModelEngine.Stats(0, 0, 0, 0, 0, 0);

    private final Transport transport;
    private final String apiKey;
    private final String model;
    private ModelEngine.Stats lastStats = ZERO;

    public CloudBrain(Transport transport, String apiKey,
                      String model) {
        this.transport = transport;
        this.apiKey = (apiKey == null) ? "" : apiKey.trim();
        this.model = (model == null || model.length() == 0)
                ? DEFAULT_MODEL : model;
    }

    /** The production brain for a stored key: the real
     *  transport, the default model. */
    public static CloudBrain fireworks(String apiKey) {
        return new CloudBrain(new HttpTransport(), apiKey,
                DEFAULT_MODEL);
    }

    /** Is a stored key value actually a key? Absent, empty
     *  and whitespace-only are all "no key" — the choice
     *  stays inert on them. */
    public static boolean keyPresent(String key) {
        return key != null && key.trim().length() > 0;
    }

    /** The model this brain asks Fireworks for. */
    public String model() {
        return model;
    }

    /** The id this brain's runs carry on their trail
     *  records — distinct from every on-device model id,
     *  so a cloud run reads as a cloud run forever. */
    public String trailModelId() {
        return TRAIL_PREFIX + model;
    }

    /**
     * The one honest status line for the brain choice,
     * shown in Settings > BRAIN. On-device: nothing leaves
     * the phone. Fireworks with a key: the sending is
     * named in plain words. Fireworks without a key: the
     * choice is inert and the line says what unblocks it.
     */
    public static String statusLine(String brain,
                                    boolean hasKey) {
        if (!Settings.BRAIN_FIREWORKS.equals(brain)) {
            return "Planner brain: on-device. The planner "
                    + "runs entirely on this phone; nothing "
                    + "leaves it.";
        }
        if (!hasKey) {
            return "Planner brain: Fireworks cloud, but no "
                    + "API key is set — runs will not start "
                    + "until a key is added below.";
        }
        return "Planner brain: Fireworks cloud ("
                + DEFAULT_MODEL + "). Each step's prompt — "
                + "the goal, the page's text and its element "
                + "map — is sent to Fireworks to plan the "
                + "step: page content leaves the phone while "
                + "this brain is selected.";
    }

    /** The feed line a cloud run opens with — the same
     *  honesty, at the run. */
    public static String runFeedLine(String trailModelId) {
        return "Brain: Fireworks cloud (" + trailModelId
                + ") — this run's prompts and page content "
                + "leave the phone.";
    }

    /**
     * The chat-completions request body for one decide:
     * the run's assembled prompt, verbatim, as the single
     * user message — the same prompt/contract the local
     * engine receives — with greedy temperature (the local
     * engine samples greedily) and the caller's token cap.
     * Pure; the harness pins its shape.
     */
    public static String requestJson(String model,
            String prompt, int maxTokens) {
        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("role", "user");
        msg.put("content", (prompt == null) ? "" : prompt);
        List<Object> messages = new ArrayList<Object>();
        messages.add(msg);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("model", (model == null || model.length() == 0)
                ? DEFAULT_MODEL : model);
        root.put("messages", messages);
        root.put("temperature", Double.valueOf(0.0));
        root.put("max_tokens", Long.valueOf(maxTokens));
        return Json.write(root);
    }

    /**
     * The planner text in a chat-completions reply body:
     * choices[0].message.content, or null when the body is
     * not that shape (an error envelope, a refusal with no
     * content, a non-string content). Pure; never throws.
     */
    public static String extractContent(String body) {
        Object root = parseQuiet(body);
        if (!(root instanceof Map)) return null;
        Object choices = ((Map<?, ?>) root).get("choices");
        if (!(choices instanceof List)
                || ((List<?>) choices).isEmpty()) return null;
        Object first = ((List<?>) choices).get(0);
        if (!(first instanceof Map)) return null;
        Object message = ((Map<?, ?>) first).get("message");
        if (!(message instanceof Map)) return null;
        Object content = ((Map<?, ?>) message).get("content");
        return (content instanceof String) ? (String) content : null;
    }

    /**
     * The reply's usage as [promptTokens, completionTokens]
     * — zeros when the body carries no usage object (the
     * trail then records zeros, as an engine error's
     * snapshot does; the timing still rides). Pure; never
     * throws.
     */
    public static long[] extractUsage(String body) {
        long[] out = new long[]{0, 0};
        Object root = parseQuiet(body);
        if (!(root instanceof Map)) return out;
        Object usage = ((Map<?, ?>) root).get("usage");
        if (!(usage instanceof Map)) return out;
        out[0] = asLong(((Map<?, ?>) usage).get("prompt_tokens"));
        out[1] = asLong(((Map<?, ?>) usage).get("completion_tokens"));
        return out;
    }

    private static Object parseQuiet(String body) {
        if (body == null) return null;
        try {
            return Json.parse(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static long asLong(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : 0;
    }

    /** A failure string's tail: one line, bounded — error
     *  bodies can be long and multi-line, and the string
     *  lands in the trail and the feed. */
    private static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return (t.length() <= max) ? t : t.substring(0, max);
    }

    /** The most recent generate() call's snapshot (zeros
     *  before the first call, and token-zeros after a
     *  failed one — its wall time still rides, as the
     *  prompt timing of record). */
    public synchronized ModelEngine.Stats lastStats() {
        return lastStats;
    }

    /**
     * Plan one step: POST the prompt to Fireworks and
     * return the reply's planner text — or an "ERROR: ..."
     * string, the engine-failure shape the loop already
     * stops on (see the class doc). Exactly one attempt.
     * Serialized, like the local engine: the planner is a
     * single conversation.
     */
    public synchronized String generate(String prompt,
                                        int maxTokens) {
        long t0 = System.currentTimeMillis();
        if (!keyPresent(apiKey)) {
            lastStats = new ModelEngine.Stats(0, 0, 0,
                    System.currentTimeMillis() - t0, 0, 0);
            return "ERROR: fireworks brain has no API key "
                    + "set (Settings > BRAIN)";
        }
        Response r;
        try {
            r = transport.post(API_URL, "Bearer " + apiKey,
                    requestJson(model, prompt, maxTokens));
        } catch (Exception e) {
            lastStats = new ModelEngine.Stats(0, 0, 0,
                    System.currentTimeMillis() - t0, 0, 0);
            return "ERROR: fireworks request failed: "
                    + oneLine(String.valueOf(
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : e.toString()), 160);
        }
        long ms = System.currentTimeMillis() - t0;
        if (r == null) {
            lastStats = new ModelEngine.Stats(0, 0, 0, ms, 0, 0);
            return "ERROR: fireworks request returned nothing";
        }
        if (r.status != 200) {
            lastStats = new ModelEngine.Stats(0, 0, 0, ms, 0, 0);
            String tail = oneLine(r.body, 160);
            return "ERROR: fireworks HTTP " + r.status
                    + (tail.length() > 0 ? ": " + tail : "");
        }
        String content = extractContent(r.body);
        if (content == null) {
            lastStats = new ModelEngine.Stats(0, 0, 0, ms, 0, 0);
            return "ERROR: fireworks reply carried no "
                    + "planner message";
        }
        long[] usage = extractUsage(r.body);
        // The Stats shape the trail already speaks: prompt
        // tokens from the API's own usage, none reused (a
        // cloud call re-sends the prompt whole), all of
        // them evaluated, the wall time as the prompt time,
        // no grammar — the cloud has no local grammar lock.
        lastStats = new ModelEngine.Stats(usage[0], 0, usage[0],
                ms, usage[1], 0, false);
        return content;
    }
}
