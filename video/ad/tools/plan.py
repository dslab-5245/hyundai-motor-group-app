"""The director (v3): turns one run's events.json + narration.json + the engine's per-frame signals into plan.json, the
layered timelines (3D scenes, flat app inserts, type in fixed zones, call-out pills, phase labels, cards, music, voice,
blinker ticks) that src/Film.tsx renders for the three compositions Ad16x9, Short9x16 and DemoFull16x9.

    python -I plan.py <public dir> <out plan.json>

Everything run-specific comes from events.json (event times, phase times, durations, gains) and narration.json (line
durations). The 3D scenes play the story recording's own music and show the recording on the car display at the same
clip time; the car, wheel, pedals, signal stalk and wipers move from signals_story.json at (clip time − scenario_offset),
where scenario_offset is measured per recording from the events the app logged (median over event types).
"""
import json, os, statistics, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lines import HASHTAGS

FPS = 30
F = lambda s: int(round(s * FPS))
BLINK_HZ = 1.5


class Timeline:
    def __init__(self, name, w, h, sources, narr, signals):
        self.name, self.w, self.h = name, w, h
        self.sources, self.narr, self.signals = sources, narr, signals
        self.t = 0.0
        self.scene3d, self.panel, self.text, self.callouts, self.labels, self.cards, self.music, self.voice, self.beats, self.ticks = [], [], [], [], [], [], [], [], [], []

    # ---- 3D scene ------------------------------------------------------------------------------------------------
    def scene(self, src, t_in, dur, keys, rain=None, start=None, fade_in=0, fade_out=0):
        """A 3D scene over the recording src from clip time t_in: camera keys [(at, cam, {cut|hold|orbit})], the
        recording's music and call-outs, and blinker ticks from the signals."""
        start = self.t if start is None else start
        s = self.sources[src]
        t_in = max(0.0, min(t_in, s["duration"] - dur))
        ks = []
        for k in keys:
            at, cam = k[0], k[1]
            flags = k[2] if len(k) > 2 else {}
            ks.append({"at": round(at, 3), "cam": cam, **{f: True for f in ("cut", "hold", "orbit") if flags.get(f)}})
        self.scene3d.append({"start": F(start), "dur": F(dur), "src": src, "offset": round(t_in, 3), "keys": ks, "rain": rain})
        self.music_seg(src, t_in, dur, start, fade_in=fade_in, fade_out=fade_out)
        self._callouts(src, t_in, dur, start)
        self._ticks(src, t_in, dur, start)
        return start + dur

    def _ticks(self, src, t_in, dur, start):
        sig = self.signals
        off = self.sources[src].get("scenario_offset")
        if not sig or off is None: return
        fps = sig["fps"]; frames = sig["frames"]
        last_phase = None
        for fr in frames:
            st = fr["t"]
            ct = st + off
            if ct < t_in or ct >= t_in + dur: continue
            if fr["signal"] == "OFF": last_phase = None; continue
            phase = int(st * BLINK_HZ)
            if phase != last_phase:
                self.ticks.append(F(start + ct - t_in)); last_phase = phase

    # ---- flat inserts ---------------------------------------------------------------------------------------------
    def shot(self, src, t_in, dur, crop="pane", style="insert", start=None, zoom=1.0, focus=None):
        """The real UI (src video) flat in its grid cell from t_in for dur seconds, plus its music and call-outs."""
        start = self.t if start is None else start
        s = self.sources[src]
        t_in = max(0.0, min(t_in, s["duration"] - dur))
        self.panel.append({"start": F(start), "dur": F(dur), "src": src, "offset": round(t_in, 3), "crop": crop, "style": style, "zoom": zoom, "focus": focus})
        self.music_seg(src, t_in, dur, start)
        self._callouts(src, t_in, dur, start)
        keys = [(0.0, self.flow_before(src, t_in))] + [(e["t"] - t_in, e["flow"]) for e in s["events"] if e["flow"] is not None and t_in <= e["t"] < t_in + dur]
        flow = []
        for k, (kt, kf) in enumerate(keys):
            flow.append({"at": F(kt), "flow": kf})
            nxt = keys[k + 1][0] if k + 1 < len(keys) else dur
            if nxt - kt > 3.0 and kf < 1.0:
                tt = kt + 2.5; ff = kf
                while tt < nxt - 0.5 and ff < 1.0:
                    ff = min(1.0, ff + 0.06); flow.append({"at": F(tt), "flow": round(ff, 3)}); tt += 1.0
        self.panel[-1]["flow"] = flow
        return start + dur

    def _callouts(self, src, t_in, dur, start):
        for e in self.sources[src]["events"]:
            if t_in <= e["t"] < t_in + dur:
                self.callouts.append({"start": F(start + e["t"] - t_in), "type": e["type"], "label": e["label"], "line": e["line"],
                                      "tone": e["tone"], "flow": e["flow"], "graced": e["graced"], "src": src, "t": e["t"]})

    def flow_before(self, src, t):
        f = 0.9
        for e in self.sources[src]["events"]:
            if e["flow"] is not None and e["t"] < t: f = e["flow"]
            if e["t"] >= t: break
        last = max([e["t"] for e in self.sources[src]["events"] if e["flow"] is not None and e["t"] < t] or [t - 100])
        return round(min(1.0, f + max(0.0, t - last - 2.5) * 0.06), 3)

    # ---- sound, type, cards -----------------------------------------------------------------------------------------
    def music_seg(self, src, t_in, dur, start, fade_in=0, fade_out=0, gain=1.0):
        """Appends music; merges with the previous segment when it continues the same source seamlessly; otherwise a
        short cross-fade hides the cut."""
        if self.music:
            m = self.music[-1]
            if m["src"] == src and abs((m["offset"] + m["dur"] / FPS) - t_in) < 1.5 / FPS and m["start"] + m["dur"] == F(start) and not m["fadeOut"]:
                m["dur"] += F(dur); m["fadeOut"] = fade_out; return
            if m["start"] + m["dur"] == F(start) and not m["fadeOut"]: m["fadeOut"] = 6
            if not fade_in: fade_in = 6
        self.music.append({"start": F(start), "dur": F(dur), "src": src, "offset": round(t_in, 3), "fadeIn": fade_in, "fadeOut": fade_out, "gain": gain})

    def say(self, nid, at=None, start=None):
        if nid not in self.narr: return (self.t if start is None else start)
        start = (self.t if start is None else start) + (at or 0.0)
        d = self.narr[nid]["duration"]
        self.voice.append({"id": nid, "start": F(start), "dur": F(d), "text": self.narr[nid]["text"]})
        return start + d

    def words(self, lines, at=0.0, dur=None, size="l", pos="message", start=None, style="kinetic"):
        start = (self.t if start is None else start) + at
        self.text.append({"start": F(start), "dur": F(dur) if dur else F(3.5), "lines": lines, "size": size, "pos": pos, "style": style})

    def label(self, label, line, at, dur, start=None):
        start = (self.t if start is None else start) + at
        self.labels.append({"start": F(start), "dur": F(dur), "label": label, "line": line})

    def card(self, kind, dur, start=None, **kw):
        start = self.t if start is None else start
        self.cards.append({"start": F(start), "dur": F(dur), "kind": kind, **kw})

    def beat(self, bid, dur):
        self.beats.append({"id": bid, "start": F(self.t), "dur": F(dur)}); return self.t

    def advance(self, dur): self.t = (F(self.t) + F(dur)) / FPS

    def out(self):
        return {"width": self.w, "height": self.h, "duration": F(self.t), "beats": self.beats, "scene3d": self.scene3d, "panel": self.panel,
                "text": self.text, "callouts": self.callouts, "labels": self.labels, "cards": self.cards, "music": self.music,
                "voice": self.voice, "ticks": sorted(set(self.ticks))}


# ---- anchors ---------------------------------------------------------------------------------------------------------
def anchors(story):
    """Story moments the beats hang on (seconds in story_clean), with fallbacks for a run that lacks one."""
    ev = story["events"]; ph = {p["label"]: p for p in story.get("phases", [])}
    def first(pred, default):
        return next((e["t"] for e in ev if pred(e)), default)
    turn = first(lambda e: e["type"] == "SIGNALED_TURN" and not e["graced"], 10.2)
    harsh0 = first(lambda e: e["tone"] == "warn" and not e["graced"] and e["t"] > turn, turn + 11.0)
    harsh1 = max([e["t"] for e in ev if e["tone"] == "warn" and not e["graced"] and harsh0 <= e["t"] < harsh0 + 15] or [harsh0 + 7.5])
    streak = first(lambda e: e["type"] == "SMOOTH_STREAK", 78.0)
    grace = first(lambda e: e["graced"], 87.0)
    layers_t0 = ph.get("레이어가 쌓입니다", {}).get("t0", turn + 3.8)
    bloom_t0 = ph.get("블룸", {}).get("t0", 57.5)
    rain_t0 = ph.get("비 · 와이퍼", {}).get("t0", 91.0)
    stop_t0 = ph.get("제동 · 정차", {}).get("t0", 100.0)
    park_t0 = ph.get("주차 (P)", {}).get("t0", 104.5)
    return dict(turn=turn, harsh0=harsh0, harsh1=harsh1, streak=streak, grace=grace, layers=layers_t0, bloom=bloom_t0, rain=rain_t0, stop=stop_t0, park=park_t0)


def remix_anchor(rx):
    ph = {p["label"]: p for p in rx.get("phases", [])}
    return next((e["t"] for e in rx["events"] if e["tone"] == "warn" and not e["graced"] and e["t"] > 5.0),
                ph.get("급조작 · 곡이 물속으로", {}).get("t0", 21.0))


def scenario_offset(seg, signals):
    """clip seconds − scenario seconds for one recording of the demo story: the median, over event types, of the first
    logged event minus the first exported event of the same type/grace (the app's control tick jitters single events)."""
    if not signals: return seg.get("scenario_t0", 1.0)
    firsts = {}
    for fr in signals["frames"]:
        if fr["event"] and (fr["event"], fr["graced"]) not in firsts: firsts[(fr["event"], fr["graced"])] = fr["t"]
    diffs = []
    seen = set()
    for e in seg["events"]:
        k = (e["type"], e["graced"])
        if k in seen or k not in firsts: continue
        seen.add(k); diffs.append(e["t"] - firsts[k])
    return round(statistics.median(diffs), 3) if diffs else seg.get("scenario_t0", 1.0)


# ---- shared sequences -----------------------------------------------------------------------------------------------
def hook_keys(total):
    """Outside the car, sliding along its flank, orbiting to the nose and in through the driver's window to the seat."""
    return [(0.0, "side", {"hold": True}), (1.2, "side"), (5.0, "frontQR", {"orbit": True}), (7.6, "frontQ", {"orbit": True}),
            (9.6, "doorGlass"), (11.2, "wide", {"hold": True}), (total, "wide")]


def stumble_keys(total):
    """Side tracking → low front quarter on the harsh steer → cut: pedal → cut: low rear (tail flare, nose dip) → pulls
    back and up to a top shot while the music sinks → down through the driver's window to the seat as it recovers."""
    return [(0.0, "sideClose", {"hold": True}), (1.3, "sideClose"), (2.4, "lowFront", {"orbit": True, "hold": True}),
            (3.0, "pedal", {"cut": True, "hold": True}), (4.7, "rearLow", {"cut": True, "hold": True}), (6.6, "rearLow"),
            (8.4, "follow"), (10.0, "top", {"hold": True}), (11.6, "top"), (13.2, "sideDriverHigh", {"orbit": True}),
            (14.6, "doorGlass"), (15.6, "wide", {"hold": True}), (total, "wide")]


def reward_keys(total):
    return [(0.0, "frontQR", {"hold": True}), (0.8, "frontQR"), (3.0, "side", {"orbit": True, "hold": True}),
            (3.8, "stalk", {"cut": True, "hold": True}), (5.0, "display", {"cut": True, "hold": True}), (total, "display")]


def arrival_keys(total):
    return [(0.0, "follow", {"hold": True}), (2.4, "follow"), (total, "top")]


# ---- compositions ---------------------------------------------------------------------------------------------------
def ad(S, narr, signals):
    story = S["story"]; A = anchors(story); off = story["scenario_offset"]; ct = lambda s: s + off
    n = lambda k: narr[k]["duration"] if k in narr else 3.5
    tl = Timeline("Ad16x9", 1920, 1080, S, narr, signals)
    # 1-2 hook + reveal: one continuous move from the highway cruise into the cabin as the melody blooms
    hook, reveal = 6.5, 7.0
    tl.beat("hook", hook)
    tl.scene("story", ct(46.0), hook + reveal, hook_keys(hook + reveal), fade_in=F(1.5))
    tl.words(["운전하는 동안,"], at=0.8, dur=2.5, size="xl")
    tl.words(["차는 당신의 리듬을", "듣고 있습니다."], at=3.5, dur=hook - 3.7, size="xl")
    tl.say("n1", at=0.8)
    tl.advance(hook)
    tl.beat("reveal", reveal)
    tl.words(["드라이브 심포니"], at=2.2, dur=reveal - 2.3, size="title", pos="title")
    tl.words(["주행 신호가 그대로", "음악이 되는 차량용 앱"], at=3.2, dur=reveal - 3.4, size="m")
    tl.say("n2", at=2.2)
    tl.advance(reveal)
    # 3 reward: the signalled turn, from outside (indicator, the car turning) then the stalk and the display
    reward = 6.5
    tl.beat("reward", reward)
    tl.scene("story", A["turn"] - 3.0, reward, reward_keys(reward))
    tl.words(["깜빡이를 켜고 도는 회전에는,", "바로 보상이 옵니다."], at=0.3, dur=reward - 0.5)
    tl.say("n5", at=0.4)
    tl.advance(reward)
    # 4 layers: the app, flat, the chips lighting up one by one
    layers = 5.5
    tl.beat("layers", layers)
    tl.shot("story", A["turn"] + 3.2, layers)
    tl.label("레이어가 쌓입니다", "베이스 · 키 · 드럼이 차례로", 0.3, layers - 0.5)
    tl.words(["부드럽게 달릴수록,", "레이어가 하나씩 열립니다."], at=0.3, dur=layers - 0.5, pos="insert")
    tl.say("n3", at=0.3)
    tl.advance(layers)
    # 5-6 stumble + recovery: the brake-stab choreography, then down through the window as the layers return
    stumble, rec = 10.0, 6.0
    tl.beat("stumble", stumble)
    tl.scene("story", A["harsh0"] - 2.2, stumble + rec, stumble_keys(stumble + rec))
    tl.words(["급제동과 급조향에는", "음악이 잠시 가라앉습니다."], at=2.0, dur=4.3)
    tl.words(["벌이 아니라,", "숨 고르기입니다."], at=6.6, dur=3.3)
    tl.label("브레이크 반복 · 습관", "테이프가 늘어지고 필터가 닫힙니다", 2.2, 7.2)
    tl.say("n4", at=2.0)
    tl.advance(stumble)
    tl.beat("recovery", rec)
    tl.words(["다시 부드럽게 달리면,", "레이어가 하나씩 돌아옵니다."], at=1.2, dur=rec - 1.5)
    tl.label("회복", "5초마다 레이어가 하나씩", 1.0, rec - 1.2)
    tl.advance(rec)
    # 7 bloom: highway, the melody
    bloom = 5.5
    tl.beat("bloom", bloom)
    tl.scene("story", ct(57.0), bloom, [(0.0, "follow", {"hold": True}), (2.59, "follow"), (2.6, "display", {"cut": True, "hold": True}),
                                         (4.19, "display"), (4.2, "followLow", {"cut": True, "hold": True}), (bloom, "followLow")])
    tl.words(["안정 주행이 이어지면,", "멜로디가 피어납니다."], at=0.3, dur=bloom - 0.5)
    tl.label("블룸", "안정 주행 → 멜로디", 0.5, bloom - 0.8)
    tl.advance(bloom)
    # 8 grace: the evasive swerve, seen from the nose then from behind as the brake lights flare
    grace = max(8.5, n("n6") + 2.4)
    tl.beat("grace", grace)
    tl.scene("story", A["grace"] - 2.2, grace, [(0.0, "frontQ", {"hold": True}), (1.6, "frontQ"), (3.0, "rear", {"orbit": True, "hold": True}), (grace, "rear")])
    tl.words(["위험을 피한 급조작은", "알아봅니다."], at=2.0, dur=3.2)
    tl.words(["감점은 절반,", "숨도 짧게."], at=5.4, dur=grace - 5.6)
    tl.say("n6", at=2.0)
    tl.advance(grace)
    # 9 rain: inside, wipers
    rain = 4.5
    tl.beat("rain", rain)
    tl.scene("story", ct(91.5), rain, [(0.0, "wide", {"hold": True}), (1.0, "wide"), (rain, "road")])
    tl.words(["비가 오면,", "비 텍스처와 셰이커가 더해집니다."], at=0.3, dur=rain - 0.5)
    tl.label("비 · 와이퍼", "", 0.3, rain - 0.5)
    tl.advance(rain)
    # 10 remix: the bundled sample track bent by the same driving (flat insert of the remix recording)
    rx = S.get("remix")
    if rx:
        remix = 7.5
        tl.beat("remix", remix)
        tl.shot("remix", max(0.0, min(remix_anchor(rx) - 2.2, rx["duration"] - remix)), remix)
        tl.label("내 음원 변주 · 샘플 곡", "Bach, Goldberg Aria (CC0)", 0.3, remix - 0.5)
        tl.words(["내 음원도,", "주행에 맞춰 변주됩니다."], at=0.3, dur=3.7, pos="insert")
        tl.words(["같은 규칙으로,", "곡이 잠시 물속에 잠깁니다."], at=4.2, dur=remix - 4.4, pos="insert")
        tl.say("n7", at=0.4)
        tl.advance(remix)
    # 11 arrival: slowing to a stop, the tail bar, up to the top; then the journey card in the app
    arr = 6.0
    tl.beat("arrival", arr)
    tl.scene("story", A["stop"] - 0.8, arr, arrival_keys(arr))
    tl.words(["도착하면,", "오늘의 여정이 한 곡으로 남습니다."], at=0.5, dur=arr - 0.7)
    tl.say("n8", at=0.5)
    tl.advance(arr)
    journey = 4.5
    tl.beat("journey", journey)
    t_j = min(A["park"] + 1.6, story["duration"] - journey)
    tl.shot("story", t_j, journey)
    tl.label("주차 (P) · 여정의 곡", "작곡실에 남습니다", 0.3, journey - 0.5)
    tl.words(["평균 Flow, 가장 길었던", "부드러운 구간까지, 한 곡으로."], at=0.4, dur=journey - 0.6, size="m", pos="insert")
    tl.advance(journey)
    # 12 end card
    end = 8.0
    tl.beat("end", end)
    rest = max(0.0, min(story["music_end"], story["duration"]) - (t_j + journey))
    if rest > 1.0: tl.music_seg("story", t_j + journey, min(rest, end), tl.t, fade_out=F(2.5))
    tl.card("end", end, title="드라이브 심포니", latin="Drive Symphony", line="주행이 연주가 되는 차", hashtags=False)
    tl.say("n9", at=0.9)
    tl.advance(end)
    return tl.out()


def short(S, narr, signals):
    story = S["story"]; A = anchors(story); off = story["scenario_offset"]; ct = lambda s: s + off
    tl = Timeline("Short9x16", 1080, 1920, S, narr, signals)
    hook = 3.0
    tl.beat("hook", hook)
    tl.scene("story", ct(46.0), hook, [(0.0, "side", {"hold": True}), (0.6, "side"), (hook, "sideClose")], fade_in=F(0.8))
    tl.words(["운전하는 동안,"], at=0.3, dur=hook - 0.5, size="xl")
    tl.advance(hook)
    # Spot: the stab, the sink, the recovery
    stumble, rec = 10.0, 6.0
    tl.beat("stumble", stumble)
    tl.scene("story", A["harsh0"] - 2.2, stumble + rec, stumble_keys(stumble + rec))
    tl.words(["급제동을 하면,"], at=0.2, dur=1.7, size="xl")
    tl.words(["음악이 잠시", "가라앉습니다."], at=2.2, dur=3.9, size="xl")
    tl.words(["벌이 아니라,", "숨 고르기입니다."], at=6.5, dur=3.3, size="xl")
    tl.say("n4", at=2.0)
    tl.advance(stumble)
    tl.beat("recovery", rec)
    tl.words(["다시 부드럽게 달리면,", "레이어가 하나씩 돌아옵니다."], at=1.2, dur=rec - 1.5)
    tl.advance(rec)
    # Solve: the reward, the layers
    reward = 6.5
    tl.beat("reward", reward)
    tl.scene("story", A["turn"] - 3.0, reward, reward_keys(reward))
    tl.words(["깜빡이를 켜고 도는 회전에는,", "바로 보상이 옵니다."], at=0.3, dur=reward - 0.5)
    tl.say("n5", at=0.4)
    tl.advance(reward)
    layers = 5.0
    tl.beat("layers", layers)
    tl.shot("story", A["turn"] + 3.2, layers)
    tl.words(["부드럽게 달릴수록,", "레이어가 하나씩 열립니다."], at=0.3, dur=layers - 0.5, pos="insert")
    tl.say("n3", at=0.3)
    tl.advance(layers)
    # Scene: arrival, end card with the hashtags
    arr = 6.0
    tl.beat("arrival", arr)
    tl.scene("story", A["stop"] - 0.8, arr, arrival_keys(arr))
    tl.words(["도착하면,", "오늘의 여정이 한 곡으로 남습니다."], at=0.5, dur=arr - 0.7)
    tl.say("n8", at=0.5)
    tl.advance(arr)
    end = 8.0
    tl.beat("end", end)
    rest = max(0.0, min(story["music_end"], story["duration"]) - (A["stop"] - 0.8 + arr))
    if rest > 1.0: tl.music_seg("story", A["stop"] - 0.8 + arr, min(rest, end), tl.t, fade_out=F(2.5))
    tl.card("end", end, title="드라이브 심포니", latin="Drive Symphony", line="주행이 연주가 되는 차", hashtags=True)
    tl.say("n9", at=0.8)
    tl.advance(end)
    return tl.out()


def demo(S, narr, signals):
    story = S["story"]; console = S.get("console"); A = anchors(story); off = story["scenario_offset"]; ct = lambda s: s + off
    tl = Timeline("DemoFull16x9", 1920, 1080, S, narr, signals)
    # intro: the same move as the ad's hook, with the title
    intro = 13.5
    tl.beat("intro", intro)
    tl.scene("story", ct(46.0), intro, hook_keys(intro), fade_in=F(1.5), fade_out=F(1.0))
    tl.words(["운전하는 동안,"], at=0.8, dur=2.5, size="xl")
    tl.words(["차는 당신의 리듬을", "듣고 있습니다."], at=3.5, dur=2.9, size="xl")
    tl.words(["드라이브 심포니"], at=8.5, dur=intro - 8.6, size="title", pos="title")
    tl.words(["주행이 연주가 되는 차"], at=9.4, dur=intro - 9.6, size="m")
    tl.say("n1", at=0.8); tl.say("n2", at=8.5)
    tl.advance(intro)
    tl.beat("chapter1", 3.5)
    tl.card("chapter", 3.5, kicker="시연 1", title="데모 시나리오", line="106초 · 시내 → 습관적 급조작 → 회복 → 고속도로 → 회피 → 비 → 도착 · 앱 자체 음원")
    tl.advance(3.5)
    sd = story["duration"]
    tl.beat("story", sd)
    tl.shot("story", 0.0, sd, crop="full", style="full")
    for p in story.get("phases", []):
        tl.label(p["label"], p["line"], p["t0"], max(2.0, p["t1"] - p["t0"]))
    tl.say("n5", at=max(0.5, A["turn"] - 0.9)); tl.say("n3", at=A["layers"]); tl.say("n4", at=A["harsh0"] - 0.3)
    tl.say("n6", at=A["grace"] - 1.2); tl.say("n8", at=A["park"])
    tl.advance(sd)
    if console:
        tl.beat("chapter2", 3.0)
        tl.card("chapter", 3.0, kicker="시연 2", title="실제 차량 신호", line="에뮬레이터 Virtual Console → Vehicle SDK → 음악 · 데모 꺼짐")
        tl.advance(3.0)
        cues = console.get("cues", [])
        cd = min(console["duration"], (cues[-1]["t1"] + 2.5) if cues else console["duration"])
        tl.beat("console", cd)
        tl.shot("console", 0.0, cd, crop="full", style="full")
        for c in cues:
            tl.label(c["label"], c["line"], c["t0"], max(2.0, c["t1"] - c["t0"]))
        tl.advance(cd)
    rx = S.get("remix")
    if rx:
        tl.beat("chapter3", 3.0)
        tl.card("chapter", 3.0, kicker="시연 3", title="내 음원 변주", line="내장 샘플 곡 Bach, Goldberg Aria (CC0) · 같은 주행 규칙으로 로우패스 · 테이프 새그 · 음량 · 스테레오 폭")
        tl.advance(3.0)
        rd = rx["duration"]
        tl.beat("remix", rd)
        tl.shot("remix", 0.0, rd, crop="full", style="full")
        for p in rx.get("phases", []):
            tl.label(p["label"], p["line"], p["t0"], max(2.0, min(p["t1"], rd) - p["t0"]))
        tl.say("n7", at=1.0)
        tl.advance(rd)
    # outro: the fullest arrangement from behind, rising to the top, then the feature card
    outro3d = 8.0
    tl.beat("outro3d", outro3d)
    tl.scene("story", ct(78.0), outro3d, [(0.0, "follow", {"hold": True}), (2.0, "follow"), (outro3d, "top")], fade_in=F(1.0))
    tl.words(["드라이브 심포니"], at=0.8, dur=outro3d - 1.0, size="title", pos="title")
    tl.say("n9", at=1.0)
    tl.advance(outro3d)
    outro = 12.0
    tl.beat("outro", outro)
    tl.music_seg("story", ct(78.0) + outro3d, outro, tl.t, fade_out=F(3.0))
    tl.card("outro", outro, title="드라이브 심포니", latin="Drive Symphony", line="주행이 연주가 되는 차",
            features=["조향 · 브레이크 · 기어 · 깜빡이 · 와이퍼 · 크루즈 · 내비 → 실시간 작곡",
                      "안전하지 않은 조작은 음악이 잠시 가라앉을 뿐, 침묵도 꾸중도 없습니다",
                      "Gleo 음성 명령 4종 · 주행 중 글랜스 화면 · 오디오 CPU 5% 미만 · 네트워크 없음"], hashtags=False)
    if not rx: tl.say("n7", at=0.6)
    tl.advance(outro)
    return tl.out()


def main():
    pub = os.path.abspath(sys.argv[1]); out = os.path.abspath(sys.argv[2])
    run_dir = os.path.join(pub, "run")
    ev = json.load(open(os.path.join(run_dir, "events.json"), encoding="utf-8"))
    nj = json.load(open(os.path.join(run_dir, "narration.json"), encoding="utf-8")) if os.path.exists(os.path.join(run_dir, "narration.json")) else {"lines": []}
    narr = {l["id"]: l for l in nj.get("lines", [])}
    sync = {}
    if os.path.exists(os.path.join(ev["run"], "sync.json")): sync = json.load(open(os.path.join(ev["run"], "sync.json")))
    sig_path = os.path.join(run_dir, "signals_story.json")
    signals = json.load(open(sig_path, encoding="utf-8")) if os.path.exists(sig_path) else None
    if not signals: raise SystemExit("public/run/signals_story.json missing: run gradle :engine:dumpSignals first")
    S = {}
    for name, seg in ev["segments"].items():
        S[name] = {"video": f"run/{seg['file']}", "audio": f"run/{seg['wav']}" if seg["wav"] else None,
                   "badge_s": seg["badge_s"], "duration": seg["duration"], "music_end": seg["music_end"],
                   "gain_db": sync.get(name, {}).get("gain_db", 0.0), "events": seg["events"], "sags": seg["sags"],
                   "phases": seg.get("phases", []), "cues": seg.get("cues", []),
                   "scenario_offset": scenario_offset(seg, signals) if name in ("story", "remix") else None}
    plan = {"fps": FPS, "sources": S, "narration": {k: {"file": f"run/{v['file']}", "duration": v["duration"], "text": v["text"]} for k, v in narr.items()},
            "voice": nj.get("voice"), "signals": "run/signals_story.json",
            "tick": "gen/tick.wav" if os.path.exists(os.path.join(pub, "gen", "tick.wav")) else None,
            "hashtags": HASHTAGS,
            "noise": "gen/noise.png" if os.path.exists(os.path.join(pub, "gen", "noise.png")) else None,
            "footer": "공모전 출품작 · 에뮬레이터 화면 녹화와 앱 자체 음원 · 차량·도로는 3D 시뮬레이션 · 출시 전 · 차량 모델 Car Concept © 2024 Darmstadt Graphics Group GmbH, CC BY 4.0 (색·등화 수정)",
            "compositions": {"Ad16x9": ad(S, narr, signals), "Short9x16": short(S, narr, signals), "DemoFull16x9": demo(S, narr, signals)}}
    json.dump(plan, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for name in ("story", "remix"):
        if name in S: print(f"{name}: scenario_offset {S[name]['scenario_offset']} s")
    for k, c in plan["compositions"].items():
        print(f"{k}: {c['duration'] / FPS:.1f} s, {len(c['scene3d'])} 3D scenes, {len(c['panel'])} inserts, {len(c['callouts'])} call-outs, "
              f"{len(c['voice'])} voice lines, {len(c['music'])} music segments, {len(c['ticks'])} ticks")
        print("   beats: " + ", ".join(f"{b['id']}@{b['start'] / FPS:.1f}" for b in c["beats"]))


if __name__ == "__main__":
    main()
