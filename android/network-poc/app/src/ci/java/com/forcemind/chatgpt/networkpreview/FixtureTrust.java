package com.forcemind.chatgpt.networkpreview;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** CI flavor only; restrict the temporary authority to the owned node listeners. */
final class FixtureTrust {
    static void apply(JSONObject settings, VlessNode node) throws JSONException {
        if ("10.0.2.2".equals(node.host) && (node.port == 19443 || node.port == 19444)) {
            settings.put("disableSystemRoot", true);
            settings.put("certificates", new JSONArray().put(new JSONObject().put("usage", "verify")
                    .put("certificate", new JSONArray(GeneratedFixtureCa.PEM.split("\\r?\\n")))));
        }
    }
}
