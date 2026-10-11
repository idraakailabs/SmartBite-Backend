package com.idraak.smartbite.v2.ai;

import java.util.List;

/** The ordered provider list (a holder, so Spring doesn't treat it as a collection of beans). */
public record ChatModels(List<ChatModel> all) {
}
