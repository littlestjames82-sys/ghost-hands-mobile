package com.ghosthands.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.34: the weather tool — the {@code weather} action's body.
 *
 * WHY IT EXISTS. The standing field test (the Oakdale
 * weather goal, GitHub issue #1) never completed honestly:
 * three device runs died in browsing machinery or ended in
 * fabrication — the trained planner read a forecast-free
 * landing page and invented the figures (v3.31's brief).
 * v3.31's grounding guard blocks the fabrication; this
 * tool gives the planner a legitimate way to KNOW: one
 * governed action fetches a real forecast from Open-Meteo
 * (keyless, public data) and the result comes back as a
 * step result like any other — except that it also joins
 * the run's read corpus (Agent's v3.31 wiring), so figures
 * quoted from it ground by construction.
 *
 * THE SHAPE. Pure: no Android, no state. The two GETs ride
 * a Transport seam — HttpTransport (HttpURLConnection) in
 * production, a fake in the host harness, which therefore
 * never touches the network. Timeouts are CloudBrain's
 * bounds (15 s connect / 60 s read): a hung socket ends
 * the lookup honestly instead of stranding the step.
 * Https only — the endpoints are constants of this class,
 * and the manifest's usesCleartextTraffic=false stands.
 *
 * RESOLUTION IS NEVER SILENT. The geocoder returns up to
 * five candidates; the tool prefers the one whose admin1
 * (state) is named in the place string ("Oakdale,
 * Tennessee" picks Tennessee's Oakdale over a same-named
 * town listed first), and otherwise takes the first
 * candidate. Either way the result's first line NAMES the
 * resolved place — "Weather for Oakdale, Tennessee,
 * United States (Open-Meteo)" — so a wrong-town resolution
 * is visible in the trail and to the planner, never
 * hidden behind the asked-for name.
 *
 * FAILURES ARE RESULTS, NOT STOPS. No candidate, a
 * transport failure, a non-200, malformed JSON, an
 * incomplete forecast — each comes back as one "error:"
 * result string telling the planner the lookup failed and
 * to browse instead; the run continues. Nothing here ever
 * throws past forecastFor, and nothing invents a figure:
 * a day whose rain chance the API does not report says
 * "chance of rain not reported", never 0.
 */
public final class WeatherTool {

    private WeatherTool() { }

    /** The keyless Open-Meteo endpoints (constants — the
     *  tool can only ever talk to these two hosts). */
    public static final String GEOCODING_URL =
            "https://geocoding-api.open-meteo.com/v1/search";
    public static final String FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast";

    /** CloudBrain's network bounds, adopted whole. */
    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 60000;

    /** One GET, abstracted so the harness can fake the
     *  wire. Returns the body on HTTP 200; throws on any
     *  failure (the message names the status or cause). */
    public interface Transport {
        String get(String url) throws Exception;
    }

    /** The production transport: one HttpURLConnection GET
     *  with the class's timeout bounds. A non-200 is a
     *  failure to report, never a body to parse. */
    public static final class HttpTransport implements Transport {
        @Override public String get(String url) throws Exception {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)
                        new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Accept",
                        "application/json");
                int status = conn.getResponseCode();
                if (status != 200) {
                    throw new Exception("HTTP " + status);
                }
                return readAll(conn.getInputStream());
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

    /** The geocoding request for a place string, exactly
     *  as the tool issues it (pinned by the harness). */
    public static String geocodingUrl(String place) {
        String encoded;
        try {
            encoded = URLEncoder.encode(place, "UTF-8");
        } catch (Exception e) {
            encoded = place; // UTF-8 is never absent
        }
        return GEOCODING_URL + "?name=" + encoded
                + "&count=5&language=en&format=json";
    }

    /** The forecast request for a resolved candidate:
     *  three days of high / low / rain chance, Fahrenheit,
     *  the place's own timezone. */
    public static String forecastUrl(double latitude,
                                     double longitude) {
        return FORECAST_URL + "?latitude="
                + Double.toString(latitude)
                + "&longitude=" + Double.toString(longitude)
                + "&daily=temperature_2m_max,"
                + "temperature_2m_min,"
                + "precipitation_probability_max"
                + "&forecast_days=3"
                + "&temperature_unit=fahrenheit"
                + "&timezone=auto";
    }

    /**
     * The action's whole body: resolve the place, fetch
     * the forecast, format the evidence. Always returns a
     * result string — an "ok:" forecast or an "error:"
     * failure that invites browsing; never throws.
     */
    public static String forecastFor(String place,
                                     Transport transport) {
        String p = (place == null) ? "" : place.trim();
        if (p.length() == 0) {
            return "error: the weather tool was given no "
                    + "place — browse for the forecast "
                    + "instead (use search).";
        }
        if (transport == null) {
            return "error: the weather lookup failed (no "
                    + "transport) — browse for the forecast "
                    + "instead (use search).";
        }
        try {
            String geoBody = transport.get(geocodingUrl(p));
            List<Map<String, Object>> candidates =
                    parseCandidates(geoBody);
            if (candidates.isEmpty()) {
                return "error: could not find a place "
                        + "called \"" + p + "\" — browse "
                        + "for the forecast instead (use "
                        + "search).";
            }
            Map<String, Object> chosen = candidates.get(
                    pickCandidate(candidates, p));
            double lat = number(chosen.get("latitude"));
            double lon = number(chosen.get("longitude"));
            String fcBody = transport.get(
                    forecastUrl(lat, lon));
            return formatForecast(chosen, fcBody);
        } catch (Exception e) {
            return "error: the weather lookup failed ("
                    + shortMessage(e) + ") — browse for "
                    + "the forecast instead (use search).";
        }
    }

    /** The geocoding reply's usable candidates: result
     *  objects carrying a name and numeric coordinates.
     *  A missing "results" key is the API's honest "none"
     *  (an empty list); a reply that is not JSON of the
     *  expected shape throws, and forecastFor turns the
     *  throw into the failure result. */
    public static List<Map<String, Object>> parseCandidates(
            String body) throws Exception {
        Object root = Json.parse(body);
        if (!(root instanceof Map)) {
            throw new Exception("geocoding reply was not "
                    + "an object");
        }
        Object results = ((Map<?, ?>) root).get("results");
        List<Map<String, Object>> out =
                new ArrayList<Map<String, Object>>();
        if (results == null) return out;
        if (!(results instanceof List)) {
            throw new Exception("geocoding results were "
                    + "not a list");
        }
        for (Object o : (List<?>) results) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) o;
            if (!(c.get("name") instanceof String)) continue;
            if (numOrNull(c.get("latitude")) == null
                    || numOrNull(c.get("longitude")) == null) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /** Which candidate the place string means: the first
     *  whose admin1 (state) the string names as a phrase
     *  of its own — "Tennessee" in "Oakdale, Tennessee" —
     *  else the first candidate (the geocoder's own best
     *  guess). The header names the winner either way. */
    public static int pickCandidate(
            List<Map<String, Object>> candidates,
            String place) {
        String p = (place == null) ? ""
                : place.toLowerCase(Locale.US);
        for (int i = 0; i < candidates.size(); i++) {
            Object a1 = candidates.get(i).get("admin1");
            if (a1 instanceof String) {
                String a = ((String) a1).trim()
                        .toLowerCase(Locale.US);
                if (a.length() > 0
                        && containsPhrase(p, a)) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** needle inside hay as a phrase: bounded on both
     *  sides by a non-letter or the string's edge, so a
     *  state named inside another word never matches. */
    public static boolean containsPhrase(String hay,
            String needle) {
        int idx = hay.indexOf(needle);
        while (idx >= 0) {
            int end = idx + needle.length();
            boolean leftOk = idx == 0
                    || !Character.isLetter(hay.charAt(idx - 1));
            boolean rightOk = end == hay.length()
                    || !Character.isLetter(hay.charAt(end));
            if (leftOk && rightOk) return true;
            idx = hay.indexOf(needle, idx + 1);
        }
        return false;
    }

    /** The forecast reply as the planner's evidence: the
     *  resolved place named on the first line, then one
     *  line per day for three days in the goal's own
     *  terms. An incomplete reply (missing or ragged
     *  daily arrays, a null temperature) throws — a
     *  partial forecast presented as the answer would be
     *  its own kind of invention; forecastFor turns the
     *  throw into the failure result. A null rain chance
     *  is NOT incomplete: it renders as not reported. */
    static String formatForecast(Map<String, Object> place,
                                 String body) throws Exception {
        Object root = Json.parse(body);
        if (!(root instanceof Map)) {
            throw new Exception("forecast reply was not "
                    + "an object");
        }
        Object dailyObj = ((Map<?, ?>) root).get("daily");
        if (!(dailyObj instanceof Map)) {
            throw new Exception("forecast reply had no "
                    + "daily block");
        }
        Map<?, ?> daily = (Map<?, ?>) dailyObj;
        List<?> time = asList(daily.get("time"));
        List<?> tmax = asList(daily.get("temperature_2m_max"));
        List<?> tmin = asList(daily.get("temperature_2m_min"));
        List<?> rain = asList(
                daily.get("precipitation_probability_max"));
        if (time == null || tmax == null || tmin == null
                || time.size() < 3 || tmax.size() < 3
                || tmin.size() < 3) {
            throw new Exception("forecast reply was "
                    + "incomplete (fewer than three days)");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ok: Weather for ");
        sb.append(placeLabel(place));
        sb.append(" (Open-Meteo)");
        for (int i = 0; i < 3; i++) {
            Object t = time.get(i);
            Double hi = (i < tmax.size())
                    ? numOrNull(tmax.get(i)) : null;
            Double lo = (i < tmin.size())
                    ? numOrNull(tmin.get(i)) : null;
            if (!(t instanceof String) || hi == null
                    || lo == null) {
                throw new Exception("forecast reply was "
                        + "incomplete (a day had no "
                        + "temperatures)");
            }
            Double pp = (rain != null && i < rain.size())
                    ? numOrNull(rain.get(i)) : null;
            sb.append('\n');
            sb.append(dayLabel((String) t));
            sb.append(": high ").append(Math.round(hi));
            sb.append("°F, low ").append(Math.round(lo));
            sb.append("°F, chance of rain ");
            if (pp == null) {
                sb.append("not reported.");
            } else {
                sb.append(Math.round(pp)).append("%.");
            }
        }
        return sb.toString();
    }

    /** "Oakdale, Tennessee, United States" — the resolved
     *  place's own name, state and country, each carried
     *  only when the geocoder supplied it. */
    static String placeLabel(Map<String, Object> place) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, place.get("name"));
        appendPart(sb, place.get("admin1"));
        appendPart(sb, place.get("country"));
        return sb.toString();
    }

    private static void appendPart(StringBuilder sb,
                                   Object part) {
        if (part instanceof String
                && ((String) part).trim().length() > 0) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(((String) part).trim());
        }
    }

    private static List<?> asList(Object o) {
        return (o instanceof List) ? (List<?>) o : null;
    }

    private static Double numOrNull(Object o) {
        return (o instanceof Number)
                ? Double.valueOf(((Number) o).doubleValue())
                : null;
    }

    private static double number(Object o) throws Exception {
        Double d = numOrNull(o);
        if (d == null) {
            throw new Exception("a coordinate was not a "
                    + "number");
        }
        return d.doubleValue();
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().length() == 0) {
            m = e.getClass().getSimpleName();
        }
        m = m.trim();
        return (m.length() > 80) ? m.substring(0, 80) : m;
    }

    // ---- dates: yyyy-MM-dd -> "Sun Nov 1" ----------------
    // Locale-free on purpose: the label is evidence text,
    // identical on every phone and in the host harness.
    // The weekday rides the days-from-civil count (1970-
    // 01-01 was a Thursday).

    private static final String[] WEEKDAYS = {
            "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    static String dayLabel(String iso) throws Exception {
        if (iso == null || iso.length() < 10) {
            throw new Exception("a forecast day had no "
                    + "date");
        }
        int y = Integer.parseInt(iso.substring(0, 4));
        int m = Integer.parseInt(iso.substring(5, 7));
        int d = Integer.parseInt(iso.substring(8, 10));
        if (m < 1 || m > 12 || d < 1 || d > 31) {
            throw new Exception("a forecast day had a "
                    + "bad date");
        }
        long days = daysFromCivil(y, m, d);
        int wd = (int) Math.floorMod(days + 4, 7);
        return WEEKDAYS[wd] + " " + MONTHS[m - 1] + " " + d;
    }

    /** Days since 1970-01-01 for a civil date (the
     *  standard days-from-civil arithmetic). */
    static long daysFromCivil(int y, int m, int d) {
        int yy = (m <= 2) ? y - 1 : y;
        long era = Math.floorDiv(yy, 400);
        int yoe = (int) (yy - era * 400);
        int mp = (m + 9) % 12;
        int doy = (153 * mp + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
