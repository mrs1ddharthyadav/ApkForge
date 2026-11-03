package com.sangam.apkforge.engine;

import com.reandroid.json.JSONArray;
import com.reandroid.json.JSONObject;

import java.io.IOException;

/**
 * Runs a chat with Claude about the open APK. Claude decides which files to read and change;
 * this class carries out those tool calls and loops until Claude has finished.
 */
public final class AiAgent {

    /** Runs a tool on the project. The app runs it on the project's own thread. */
    public interface ToolRunner {
        AiTools.Result run(String name, JSONObject input) throws Exception;
    }

    /** Progress reported to the chat screen. Called on the agent's thread. */
    public interface Listener {
        void onText(String text);

        void onToolStarted(String description);

        void onToolFinished(String description, boolean error);

        void onUsage(long inputTokens, long outputTokens);
    }

    /** Most rounds of tool calls for one request, so a confused model can't loop forever. */
    private static final int MAX_ROUNDS = 40;
    private static final int MAX_TOKENS = 16000;

    private final ClaudeClient client;
    private final String model;
    private final String appSummary;
    private final JSONArray messages = new JSONArray();
    private long totalInput;
    private long totalOutput;

    public AiAgent(ClaudeClient client, String model, String appSummary) {
        this.client = client;
        this.model = model;
        this.appSummary = appSummary;
    }

    public void cancel() {
        client.cancel();
    }

    public long getTotalInputTokens() {
        return totalInput;
    }

    public long getTotalOutputTokens() {
        return totalOutput;
    }

    /** Sends one request from the user and works on it until Claude is done. */
    public void run(String prompt, ToolRunner tools, Listener listener) throws IOException {
        client.resetCancel();
        JSONArray content = new JSONArray();
        content.put(textBlock(prompt));
        messages.put(message("user", content));

        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                if (client.isCancelled()) throw new IOException("Stopped.");
                JSONObject response = client.send(buildRequest());
                JSONObject usage = response.optJSONObject("usage");
                if (usage != null) {
                    long in = usage.optLong("input_tokens", 0)
                            + usage.optLong("cache_read_input_tokens", 0)
                            + usage.optLong("cache_creation_input_tokens", 0);
                    long out = usage.optLong("output_tokens", 0);
                    totalInput += in;
                    totalOutput += out;
                    listener.onUsage(totalInput, totalOutput);
                }

                JSONArray blocks = response.optJSONArray("content");
                if (blocks == null) blocks = new JSONArray();
                messages.put(message("assistant", blocks));

                JSONArray results = new JSONArray();
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject b = blocks.optJSONObject(i);
                    if (b == null) continue;
                    String type = b.optString("type", "");
                    if ("text".equals(type)) {
                        String t = b.optString("text", "").trim();
                        if (!t.isEmpty()) listener.onText(t);
                    } else if ("tool_use".equals(type)) {
                        results.put(runTool(b, tools, listener, response.optString("stop_reason", "")));
                    }
                }

                String stop = response.optString("stop_reason", "");
                if (results.length() > 0) {
                    messages.put(message("user", results));
                    continue;
                }
                if ("max_tokens".equals(stop)) {
                    listener.onText("(Claude's answer was cut off because it was very long. Ask it to continue.)");
                } else if ("refusal".equals(stop)) {
                    listener.onText("(Claude declined to do this.)");
                }
                return;
            }
            listener.onText("I stopped after many steps so this doesn't run up your bill. "
                    + "Tell me to continue if you want me to keep going.");
        } catch (IOException e) {
            removeUnansweredToolCalls();
            throw e;
        }
    }

    private JSONObject runTool(JSONObject block, ToolRunner tools, Listener listener, String stopReason) {
        String id = block.optString("id");
        String name = block.optString("name");
        JSONObject input = block.optJSONObject("input");
        if (input == null) input = new JSONObject();
        String description = AiTools.describe(name, input);

        JSONObject result = new JSONObject();
        result.put("type", "tool_result");
        result.put("tool_use_id", id);

        if ("max_tokens".equals(stopReason)) {
            // The tool call itself was cut off, so its input is incomplete.
            result.put("content", "Error: your message was cut off before this tool call was complete. "
                    + "Make smaller changes per call (for example fewer strings or edit_file instead of write_file).");
            result.put("is_error", true);
            listener.onToolFinished(description, true);
            return result;
        }

        listener.onToolStarted(description);
        AiTools.Result r;
        try {
            r = tools.run(name, input);
        } catch (Exception e) {
            r = null;
            result.put("content", "Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            result.put("is_error", true);
        }
        if (r != null) {
            result.put("content", r.text);
            if (r.error) result.put("is_error", true);
        }
        listener.onToolFinished(description, r == null || r.error);
        return result;
    }

    /**
     * If a request failed half way, the history may end with tool calls that never got results.
     * The API rejects that, so drop the incomplete turn and keep the chat usable.
     */
    private void removeUnansweredToolCalls() {
        int last = messages.length() - 1;
        if (last < 0) return;
        JSONObject m = messages.optJSONObject(last);
        if (m != null && "assistant".equals(m.optString("role"))) {
            JSONArray c = m.optJSONArray("content");
            boolean hasTool = false;
            for (int i = 0; c != null && i < c.length(); i++) {
                JSONObject b = c.optJSONObject(i);
                if (b != null && "tool_use".equals(b.optString("type"))) hasTool = true;
            }
            if (hasTool) {
                messages.remove(last);
                JSONArray note = new JSONArray();
                note.put(textBlock("(The previous step was interrupted before it finished.)"));
                messages.put(message("user", note));
                JSONArray ack = new JSONArray();
                ack.put(textBlock("Understood."));
                messages.put(message("assistant", ack));
            }
        }
    }

    private JSONObject buildRequest() {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);

        JSONArray system = new JSONArray();
        JSONObject sys = textBlock(systemPrompt());
        sys.put("cache_control", ephemeral());
        system.put(sys);
        body.put("system", system);

        JSONArray tools = AiTools.definitions();
        tools.getJSONObject(tools.length() - 1).put("cache_control", ephemeral());
        body.put("tools", tools);

        // Copy the history and mark only the newest block for caching (the API allows 4 marks).
        JSONArray msgs = new JSONArray(messages.toString());
        JSONObject lastMsg = msgs.optJSONObject(msgs.length() - 1);
        if (lastMsg != null) {
            JSONArray c = lastMsg.optJSONArray("content");
            if (c != null && c.length() > 0) c.getJSONObject(c.length() - 1).put("cache_control", ephemeral());
        }
        body.put("messages", msgs);
        return body;
    }

    private String systemPrompt() {
        return "You are the AI assistant inside APK Forge, an Android app the user runs on their phone to edit APK files. "
                + "The user has opened one APK in the editor. They are not a programmer: they describe what they want in "
                + "everyday words and you find the right files and make the change yourself with the tools.\n\n"
                + "The open APK:\n" + appSummary + "\n\n"
                + "How a compiled APK is organised:\n"
                + "- AndroidManifest.xml: app name label, permissions, activities (screens), the launcher icon.\n"
                + "- res/layout*/: screen layouts as XML. Look for activity_*, fragment_*, main*.\n"
                + "- res/drawable*/ and res/color*/: shapes, backgrounds, selectors and color lists as XML; images (.png/.webp).\n"
                + "- Texts, colors, sizes, true/false switches and numbers are NOT separate files. They live in the resource "
                + "table: use list_strings / set_strings for texts and list_values / set_values for color, dimen, bool, integer. "
                + "Theme colors usually have names like colorPrimary, colorAccent, primary, accent, background.\n"
                + "- classes*.dex is compiled code. It can't be read or changed yet, so you can't change app behaviour that "
                + "lives only in code. Say so plainly if a request needs it.\n"
                + "- Images can't be drawn or edited by you. The user can replace an image in the Files tab; tell them which file.\n\n"
                + "Rules:\n"
                + "1. Look before you change: find the right file with find_files, search_text or list_files, and read it.\n"
                + "2. Prefer small edits with edit_file. Keep XML valid; a broken file is rejected and you'll see the error.\n"
                + "3. When translating, use set_strings with the language code and keep placeholders (%s, %d, %1$s) and \\n as they are. "
                + "Work through long lists in batches of up to 150.\n"
                + "4. Don't delete files or make changes the user didn't ask for. If the request is unclear, ask one short question first.\n"
                + "5. Changes stay in the editor until the user taps Build (top right). Remind them once when you've finished making changes.\n"
                + "6. Reply in short, simple sentences for a non-programmer. Use plain text only: no Markdown, no ** or #, no code blocks. "
                + "After making changes, list what you changed in a few plain lines.\n"
                + "7. Don't help bypass licence checks, payments, or other people's protections; you can explain why you won't.";
    }

    private static JSONObject ephemeral() {
        JSONObject o = new JSONObject();
        o.put("type", "ephemeral");
        return o;
    }

    private static JSONObject textBlock(String text) {
        JSONObject o = new JSONObject();
        o.put("type", "text");
        o.put("text", text);
        return o;
    }

    private static JSONObject message(String role, JSONArray content) {
        JSONObject m = new JSONObject();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Builds the short APK description that goes into the system prompt. */
    public static String summarize(ApkProject project, String label) {
        int xml = 0, images = 0, code = 0, other = 0;
        for (ApkProject.FileItem f : project.listFiles()) {
            switch (f.kind) {
                case BINARY_XML:
                case TEXT:
                    xml++;
                    break;
                case IMAGE:
                    images++;
                    break;
                case DEX:
                    code++;
                    break;
                default:
                    other++;
            }
        }
        StringBuilder langs = new StringBuilder();
        try {
            java.util.List<String> l = project.listStringLanguages();
            for (int i = 0; i < l.size() && i < 40; i++) {
                if (i > 0) langs.append(", ");
                langs.append(ApkProject.describeQualifiers(l.get(i)));
            }
            if (l.size() > 40) langs.append(", and ").append(l.size() - 40).append(" more");
        } catch (Exception e) {
            langs.append("unknown");
        }
        return "Name: " + label + "\nPackage: " + project.getPackageName()
                + "\nFiles: " + xml + " text/XML, " + images + " images, " + code + " code (.dex), " + other + " other"
                + "\nText languages: " + langs;
    }
}
