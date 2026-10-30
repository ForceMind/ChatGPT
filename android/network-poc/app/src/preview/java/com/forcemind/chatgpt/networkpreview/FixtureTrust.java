package com.forcemind.chatgpt.networkpreview;

import org.json.JSONObject;

/** The installable preview never contains or trusts the CI authority. */
final class FixtureTrust {
    static void apply(JSONObject settings, VlessNode node) { }
}
