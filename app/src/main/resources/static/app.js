const KNOWN_PROFILES = [
  "qwen2.5-1.5b",
  "qwen2.5-3b",
  "qwen3-4b",
  "phi-4-mini",
];

const els = {
  badge: document.getElementById("model-badge"),
  stateDot: document.getElementById("state-dot"),
  select: document.getElementById("model-select"),
  notice: document.getElementById("model-notice"),
  messages: document.getElementById("messages"),
  input: document.getElementById("input"),
  send: document.getElementById("send"),
  systemPrompt: document.getElementById("system-prompt"),
  maxTokens: document.getElementById("max-tokens"),
  temperature: document.getElementById("temperature"),
  topK: document.getElementById("top-k"),
  topP: document.getElementById("top-p"),
  stream: document.getElementById("stream"),
  stats: document.getElementById("stats"),
};

const history = [];
let activeModel = null;
let busy = false;

function setState(state) {
  els.stateDot.className = "state-dot state-" + state;
  els.stateDot.title = "server: " + state;
}

function setBusy(value) {
  busy = value;
  els.send.disabled = value;
  els.input.disabled = value;
}

function autoGrow() {
  els.input.style.height = "auto";
  els.input.style.height = Math.min(els.input.scrollHeight, 200) + "px";
}

function scrollToBottom() {
  els.messages.scrollTop = els.messages.scrollHeight;
}

function addBubble(role, text) {
  const wrap = document.createElement("div");
  wrap.className = "msg msg-" + role;
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  wrap.appendChild(bubble);
  els.messages.appendChild(wrap);
  scrollToBottom();
  return { wrap, bubble };
}

function addMeta(wrap, text) {
  const meta = document.createElement("div");
  meta.className = "meta";
  meta.textContent = text;
  wrap.appendChild(meta);
  scrollToBottom();
}

function addToolCalls(wrap, toolCalls) {
  toolCalls.forEach((tc) => {
    const fn = tc.function || {};
    const block = document.createElement("div");
    block.className = "tool-call";
    const name = document.createElement("div");
    name.className = "tool-name";
    name.textContent = "tool_call → " + (fn.name || "?");
    const args = document.createElement("pre");
    args.className = "tool-args";
    args.textContent = prettyJson(fn.arguments);
    block.appendChild(name);
    block.appendChild(args);
    wrap.appendChild(block);
  });
  scrollToBottom();
}

function prettyJson(text) {
  if (!text) return "{}";
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch (e) {
    return text;
  }
}

function addSpinner() {
  const wrap = document.createElement("div");
  wrap.className = "msg msg-assistant";
  const bubble = document.createElement("div");
  bubble.className = "bubble thinking";
  bubble.innerHTML = '<span class="dot"></span><span class="dot"></span><span class="dot"></span>';
  wrap.appendChild(bubble);
  els.messages.appendChild(wrap);
  scrollToBottom();
  return wrap;
}

function buildModelSelect() {
  els.select.innerHTML = "";
  const ids = new Set(KNOWN_PROFILES);
  if (activeModel) ids.add(activeModel);
  ids.forEach((id) => {
    const opt = document.createElement("option");
    opt.value = id;
    opt.textContent = id === activeModel ? id + " (active)" : id;
    els.select.appendChild(opt);
  });
  if (activeModel) els.select.value = activeModel;
  updateModelNotice();
}

function updateModelNotice() {
  const chosen = els.select.value;
  if (activeModel && chosen !== activeModel) {
    els.notice.hidden = false;
    els.notice.textContent =
      "restart with --spring.profiles.active=" + chosen + " to use this model";
  } else {
    els.notice.hidden = true;
    els.notice.textContent = "";
  }
}

async function loadModels() {
  try {
    const res = await fetch("/v1/models");
    if (!res.ok) throw new Error("status " + res.status);
    const data = await res.json();
    const id = data && data.data && data.data[0] && data.data[0].id;
    activeModel = id || "unknown";
    els.badge.textContent = activeModel;
    els.badge.title = "active model: " + activeModel;
    setState("ready");
  } catch (e) {
    els.badge.textContent = "unavailable";
    setState("offline");
  } finally {
    buildModelSelect();
  }
}

function buildPayload(stream) {
  // Prepend the system prompt as a system message on every turn (cheap; the
  // server formats it via the model's chat template).
  const messages = [];
  const sys = els.systemPrompt.value.trim();
  if (sys) messages.push({ role: "system", content: sys });
  history.forEach((m) => messages.push(m));

  const payload = {
    messages,
    stream,
    max_tokens: parseInt(els.maxTokens.value, 10) || 256,
    temperature: parseFloat(els.temperature.value) || 0.7,
    top_k: parseInt(els.topK.value, 10) || 40,
    top_p: parseFloat(els.topP.value) || 0.95,
  };
  if (activeModel) payload.model = activeModel;
  return payload;
}

async function send() {
  if (busy) return;
  const text = els.input.value.trim();
  if (!text) return;

  addBubble("user", text);
  history.push({ role: "user", content: text });
  els.input.value = "";
  autoGrow();
  setBusy(true);

  const wantStream = els.stream.checked;
  const spinner = addSpinner();
  const payload = buildPayload(wantStream);

  try {
    const res = await fetch("/v1/chat/completions", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: wantStream ? "text/event-stream" : "application/json" },
      body: JSON.stringify(payload),
    });

    if (res.status === 503) {
      spinner.remove();
      const body = await res.json().catch(() => ({}));
      setState("loading");
      addBubble("error", "Model still loading" + (body.state ? " (" + body.state + ")" : "") + ", retrying…");
      history.pop();
      els.input.value = text;
      setTimeout(() => setBusy(false), 4000);
      return;
    }

    if (!res.ok) {
      spinner.remove();
      const body = await res.text().catch(() => "");
      addBubble("error", "Request failed (" + res.status + "). " + body.slice(0, 200));
      history.pop();
      setBusy(false);
      return;
    }

    if (wantStream) {
      await consumeStream(res, spinner);
    } else {
      await consumeJson(res, spinner);
    }
  } catch (e) {
    spinner.remove();
    addBubble("error", "Network error: " + e.message);
    history.pop();
    setBusy(false);
  }
}

async function consumeJson(res, spinner) {
  const data = await res.json();
  const choice = data.choices && data.choices[0];
  const message = choice && choice.message ? choice.message : {};
  spinner.remove();
  setState("ready");

  const reply = message.content || "";
  const { wrap } = addBubble("assistant", reply || (message.tool_calls ? "" : "(empty response)"));
  if (message.tool_calls && message.tool_calls.length) {
    addToolCalls(wrap, message.tool_calls);
  }
  history.push({ role: "assistant", content: reply });
  if (data.usage) {
    addMeta(
      wrap,
      "tokens: " + data.usage.prompt_tokens + " in / " +
        data.usage.completion_tokens + " out / " + data.usage.total_tokens + " total" +
        (choice && choice.finish_reason ? " · " + choice.finish_reason : "")
    );
  }
  setBusy(false);
}

async function consumeStream(res, spinner) {
  spinner.remove();
  setState("ready");
  const { wrap, bubble } = addBubble("assistant", "");
  let acc = "";
  let finishReason = null;

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // SSE frames are separated by blank lines; each line of interest is `data: ...`.
    let idx;
    while ((idx = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line.startsWith("data:")) continue;
      const payload = line.slice("data:".length).trim();
      if (payload === "[DONE]") continue;
      try {
        const chunk = JSON.parse(payload);
        const c = chunk.choices && chunk.choices[0];
        if (!c) continue;
        if (c.delta && typeof c.delta.content === "string") {
          acc += c.delta.content;
          bubble.textContent = acc;
          scrollToBottom();
        }
        if (c.finish_reason) finishReason = c.finish_reason;
      } catch (e) {
        // ignore partial/non-JSON frames
      }
    }
  }

  history.push({ role: "assistant", content: acc });
  if (finishReason) addMeta(wrap, "finish_reason: " + finishReason);
  setBusy(false);
}

const STATES = ["DOWNLOADING", "LOADING", "READY", "FAILED"];

async function metricValue(name) {
  const res = await fetch("/actuator/metrics/" + name);
  if (!res.ok) throw new Error("status " + res.status);
  const data = await res.json();
  const m = data.measurements && data.measurements[0];
  return m ? m.value : 0;
}

async function refreshStats() {
  try {
    const [tps, stateOrdinal] = await Promise.all([
      metricValue("mochallama.tokens_per_second"),
      metricValue("mochallama.model.state"),
    ]);
    const state = STATES[Math.round(stateOrdinal)] || "?";
    els.stats.textContent =
      tps.toFixed(1) + " tok/s · model: " + (activeModel || "?") + " · " + state;
  } catch (e) {
    els.stats.textContent = "stats unavailable";
  }
}

els.send.addEventListener("click", send);
els.input.addEventListener("input", autoGrow);
els.input.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    send();
  }
});
els.select.addEventListener("change", updateModelNotice);

loadModels();
refreshStats();
setInterval(refreshStats, 3000);
