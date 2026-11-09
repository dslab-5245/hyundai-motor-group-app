"""The director: turns one run's events.json + narration.json + the b-roll list into plan.json, the layered timelines
(background, panel shots, kinetic text, call-outs, labels, cards, music, voice) that src/Film.tsx renders for the three
compositions Ad16x9, Short9x16 and DemoFull16x9.

    python -I plan.py <public dir> <out plan.json>

Everything run-specific comes from events.json (event times, phase times, durations, gains) and narration.json
(line durations); beats are anchored on events (first signalled turn, first harsh event, smooth streak, graced event,
arrival phase) with fallbacks when a run lacks one.
"""
import json, os, subprocess, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lines import HASHTAGS

FPS = 30
F = lambda s: int(round(s * FPS))


def probe(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True)
    return float(r.stdout.strip() or 0)


class Timeline:
    def __init__(self, name, w, h, sources, narr, broll):
        self.name, self.w, self.h = name, w, h
        self.sources, self.narr, self.broll = sources, narr, broll
        self.t = 0.0
        self.bg, self.panel, self.text, self.callouts, self.labels, self.cards, self.music, self.voice, self.beats = [], [], [], [], [], [], [], [], []

    # ---- helpers -------------------------------------------------------------------------------------------------
    def clip(self, role, k=0):
        ids = [c for c in self.broll["order"] if role in self.broll["clips"][c]["roles"]]
        ids += [c for c in self.broll["order"] if c not in ids and not ({"rain", "arrival"} & set(self.broll["clips"][c]["roles"]))]
        if not ids: ids = self.broll["order"]
        return ids[k % len(ids)]

    def background(self, roles, dur, start=None, offset=0.0, zoom=1.06, dim=1.0, blur=0):
        """Fills dur seconds from start with clips of the given roles (chained with crossfades when one is too short).
        A clip is never read past its end: the offset moves back when it fits, otherwise the next clip is chained."""
        start = self.t if start is None else start
        left = dur; t = start; k = 0; overlap = 0.34
        roles = roles if isinstance(roles, list) else [roles]
        while left > 0.02 and k < 12:
            cid = self.clip(roles[k % len(roles)], k // len(roles))
            cdur = self.broll["clips"][cid]["duration"] - 0.5
            off = offset if k == 0 else 0.3
            if off + left > cdur: off = max(0.0, cdur - left)          # fits when started earlier
            take = min(left, cdur - off)
            more = left - take > 0.02
            self.bg.append({"start": F(t), "dur": F(take) + (F(overlap) if more else 0), "clip": cid, "offset": round(off, 3),
                            "zoom": zoom, "dim": dim, "blur": blur, "fadeIn": F(overlap) if k > 0 else 0})
            t += take; left -= take; k += 1

    def shot(self, src, t_in, dur, crop="pane", style="float", enter="cut", exit="cut", start=None, zoom=1.0, focus=None):
        """A panel shot: the real UI (src video) from t_in for dur seconds, plus its music and call-outs."""
        start = self.t if start is None else start
        s = self.sources[src]
        t_in = max(0.0, min(t_in, s["duration"] - dur))
        self.panel.append({"start": F(start), "dur": F(dur), "src": src, "offset": round(t_in, 3), "crop": crop, "style": style,
                           "enter": enter, "exit": exit, "zoom": zoom, "focus": focus})
        self.music_seg(src, t_in, dur, start)
        for e in s["events"]:
            if t_in <= e["t"] < t_in + dur:
                self.callouts.append({"start": F(start + e["t"] - t_in), "type": e["type"], "label": e["label"], "line": e["line"],
                                      "tone": e["tone"], "flow": e["flow"], "graced": e["graced"], "src": src, "t": e["t"]})
        # flow keys (the panel dips with the app's flow): the value at the shot start, every event, slow recovery after
        keys = [(0.0, self.flow_before(src, t_in))] + [(e["t"] - t_in, e["flow"]) for e in s["events"] if e["flow"] is not None and t_in <= e["t"] < t_in + dur]
        flow = []
        for k, (kt, kf) in enumerate(keys):
            flow.append({"at": F(kt), "flow": kf})
            nxt = keys[k + 1][0] if k + 1 < len(keys) else dur
            if nxt - kt > 3.0 and kf < 1.0:      # recovery ramp: +0.06 per second after 2.5 s without events
                tt = kt + 2.5; ff = kf
                while tt < nxt - 0.5 and ff < 1.0:
                    ff = min(1.0, ff + 0.06); flow.append({"at": F(tt), "flow": round(ff, 3)}); tt += 1.0
        self.panel[-1]["flow"] = flow
        return start + dur

    def flow_before(self, src, t):
        f = 0.9
        for e in self.sources[src]["events"]:
            if e["flow"] is not None and e["t"] < t: f = e["flow"]
            if e["t"] >= t: break
        # recovery since that event
        last = max([e["t"] for e in self.sources[src]["events"] if e["flow"] is not None and e["t"] < t] or [t - 100])
        return round(min(1.0, f + max(0.0, t - last - 2.5) * 0.06), 3)

    def music_seg(self, src, t_in, dur, start, fade_in=0, fade_out=0, gain=1.0):
        """Appends music; merges with the previous segment when it continues the same source seamlessly."""
        if self.music:
            m = self.music[-1]
            if m["src"] == src and abs((m["offset"] + m["dur"] / FPS) - t_in) < 1.5 / FPS and m["start"] + m["dur"] == F(start) and not m["fadeOut"]:
                m["dur"] += F(dur); m["fadeOut"] = fade_out; return
        self.music.append({"start": F(start), "dur": F(dur), "src": src, "offset": round(t_in, 3), "fadeIn": fade_in, "fadeOut": fade_out, "gain": gain})

    def say(self, nid, at=None, start=None):
        """Voice line nid at (scene start + at); returns its end time."""
        if nid not in self.narr: return (self.t if start is None else start)
        start = (self.t if start is None else start) + (at or 0.0)
        d = self.narr[nid]["duration"]
        self.voice.append({"id": nid, "start": F(start), "dur": F(d), "text": self.narr[nid]["text"]})
        return start + d

    def words(self, lines, at=0.0, dur=None, size="l", pos="left", start=None, style="kinetic"):
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

    def advance(self, dur): self.t = (F(self.t) + F(dur)) / FPS   # frame-quantised cursor: no 1-frame gaps between beats

    def out(self):
        total = F(self.t)
        return {"width": self.w, "height": self.h, "duration": total, "beats": self.beats, "background": self.bg, "panel": self.panel,
                "text": self.text, "callouts": self.callouts, "labels": self.labels, "cards": self.cards, "music": self.music,
                "voice": self.voice}


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
    stop_t0 = ph.get("제동 · 정차", {}).get("t0", 100.0)
    park_t0 = ph.get("주차 (P)", {}).get("t0", 104.5)
    return dict(turn=turn, harsh0=harsh0, harsh1=harsh1, streak=streak, grace=grace, layers=layers_t0, bloom=bloom_t0, stop=stop_t0, park=park_t0)


def ad(sources, narr, broll):
    story = sources["story"]; A = anchors(story); n = lambda k: narr[k]["duration"] if k in narr else 3.5
    tl = Timeline("Ad16x9", 1920, 1080, sources, narr, broll)
    t_reveal = max(story["badge_s"] + 0.15, A["turn"] - 1.1)
    # 1 hook: pure POV, then line 1
    hook = min(8.0, t_reveal - story["badge_s"]) if t_reveal - story["badge_s"] > 4 else 8.0
    tl.beat("hook", hook)
    tl.background("hero", hook + 7.5, offset=1.0, zoom=1.08, dim=0.85, blur=2)
    tl.music_seg("story", max(0.0, t_reveal - hook), hook, tl.t, fade_in=F(1.5))
    tl.words(["운전하는 동안,"], at=2.2, dur=2.6, size="xl", pos="center")
    tl.words(["차는 당신의 리듬을", "듣고 있습니다."], at=4.6, dur=hook - 4.6, size="xl", pos="center")
    tl.say("n1", at=2.1)
    tl.advance(hook)
    # 2 reveal: the display flies in, title, first call-out (signalled turn)
    tl.beat("reveal", 7.5)
    tl.shot("story", t_reveal, 7.5, style="reveal", enter="fly")
    tl.words(["드라이브 심포니"], at=0.35, dur=7.0, size="title", pos="topleft")
    tl.words(["주행 신호가 그대로", "음악이 되는 차량용 앱"], at=2.2, dur=5.0, size="m", pos="topleft2")
    tl.say("n2", at=1.3)
    tl.advance(7.5)
    # 3 earned layers (continuous shot)
    layers = max(3.5, A["harsh0"] - (t_reveal + 7.5))
    tl.beat("layers", layers)
    tl.background("city", layers, dim=0.7, blur=4)
    tl.shot("story", t_reveal + 7.5, layers)
    tl.words(["부드럽게 달릴수록,", "레이어가 하나씩 열립니다."], at=0.2, dur=layers - 0.3, size="l", pos="left")
    tl.say("n3", at=0.3)
    tl.advance(layers)
    # 4 stumble: the harsh section, panel dips, call-outs on every event
    stumble = min(10.5, max(8.0, A["harsh1"] - A["harsh0"] + 1.8))
    tl.beat("stumble", stumble)
    tl.background(["hero", "city"], stumble, offset=7.4, dim=0.6, blur=4)
    tl.shot("story", A["harsh0"], stumble, style="dip")
    tl.words(["급제동과 급조향에는", "음악이 잠시 가라앉습니다."], at=0.2, dur=4.3, size="l", pos="left")
    tl.words(["벌이 아니라,", "숨 고르기입니다."], at=4.8, dur=stumble - 5.0, size="l", pos="left")
    tl.say("n4", at=0.4)
    tl.advance(stumble)
    # 5 recovery (continuous, no voice)
    rec = 6.5
    tl.beat("recovery", rec)
    tl.background("window", rec, dim=0.7, blur=3)
    tl.shot("story", A["harsh0"] + stumble, rec)
    tl.words(["다시 부드럽게 달리면,", "레이어가 하나씩 돌아옵니다."], at=0.6, dur=rec - 0.8, size="m", pos="left")
    tl.advance(rec)
    # 6 bloom: highway, the melody blooms
    bloom = 6.0
    tl.beat("bloom", bloom)
    tl.background("highway", bloom, dim=0.75, blur=3)
    tl.shot("story", A["bloom"] + 2.0, bloom, enter="flash")
    tl.words(["안정 주행이 이어지면,", "멜로디가 피어납니다."], at=0.5, dur=bloom - 0.7, size="m", pos="left")
    tl.advance(bloom)
    # 7 reward: macro on the ring at the signalled turn
    reward = 6.0
    tl.beat("reward", reward)
    tl.background("city", reward, offset=0.4, dim=0.7, blur=4)
    tl.shot("story", A["turn"] - 1.0, reward, style="macro", enter="flash", zoom=1.75, focus=[0.30, 0.40])
    tl.words(["깜빡이를 켜고 도는 회전에는,", "바로 보상이 옵니다."], at=0.3, dur=reward - 0.5, size="l", pos="left")
    tl.say("n5", at=0.3)
    tl.advance(reward)
    # 8 grace
    grace = max(6.5, n("n6") + 0.9)
    tl.beat("grace", grace)
    tl.background("window", grace, offset=6.0, dim=0.7, blur=3)
    tl.shot("story", A["grace"] - 1.6, grace, enter="flash")
    tl.words(["위험을 피한 급조작은", "알아봅니다."], at=0.3, dur=3.4, size="l", pos="left")
    tl.words(["감점은 절반,", "숨도 짧게."], at=3.9, dur=grace - 4.1, size="l", pos="left")
    tl.say("n6", at=0.3)
    tl.advance(grace)
    # 9 remix: typographic beat over rain glass, music continues
    remix = 6.0
    tl.beat("remix", remix)
    tl.background("rain", remix, dim=0.8, zoom=1.1, blur=1)
    tl.music_seg("story", A["grace"] - 1.6 + grace, remix, tl.t)
    tl.words(["내 음원도,", "주행에 맞춰 변주됩니다."], at=0.3, dur=remix - 0.4, size="xl", pos="center")
    tl.say("n7", at=0.4)
    tl.advance(remix)
    # 10 arrival
    arr = 8.0
    t_arr = A["grace"] - 1.6 + grace + remix
    if abs(t_arr - A["stop"]) > 6.0: t_arr = A["stop"] - 1.0
    tl.beat("arrival", arr)
    tl.background("arrival", arr, offset=2.0, dim=0.7, blur=2)
    tl.shot("story", t_arr, arr, style="settle", enter="rise", exit="sink")
    tl.words(["도착하면,", "오늘의 여정이 한 곡으로 남습니다."], at=0.5, dur=arr - 0.7, size="l", pos="left")
    tl.say("n8", at=0.5)
    tl.advance(arr)
    # 11 end card
    end = 9.0
    tl.beat("end", end)
    rest = max(0.0, min(story["music_end"], story["duration"]) - (t_arr + arr))
    tl.music_seg("story", t_arr + arr, min(rest, end), tl.t, fade_out=F(2.5))
    tl.card("end", end, title="드라이브 심포니", latin="Drive Symphony", line="주행이 연주가 되는 차", hashtags=False)
    tl.say("n9", at=0.9)
    tl.advance(end)
    return tl.out()


def short(sources, narr, broll):
    story = sources["story"]; A = anchors(story); n = lambda k: narr[k]["duration"] if k in narr else 3.5
    tl = Timeline("Short9x16", 1080, 1920, sources, narr, broll)
    # Spot
    t_st = A["harsh0"] - 0.8
    tl.beat("spot", 2.5)
    tl.background("hero", 2.5 + 9.5, offset=2.0, zoom=1.08, dim=0.8, blur=7)
    tl.music_seg("story", t_st - 2.5, 2.5, tl.t, fade_in=F(0.8))
    tl.words(["급제동을 하면,"], at=0.3, dur=2.2, size="xl", pos="center")
    tl.advance(2.5)
    tl.beat("stumble", 9.5)
    tl.shot("story", t_st, 9.5, style="dip", enter="fly")
    tl.words(["음악이 잠시", "가라앉습니다."], at=0.2, dur=4.0, size="xl", pos="top")
    tl.words(["벌이 아니라,", "숨 고르기입니다."], at=4.5, dur=4.8, size="xl", pos="top")
    tl.say("n4", at=0.3)
    tl.advance(9.5)
    # Solve
    tl.beat("recovery", 5.0)
    tl.background("window", 5.0, dim=0.7, blur=3)
    tl.shot("story", t_st + 9.5, 5.0)
    tl.words(["다시 부드럽게 달리면,", "레이어가 하나씩 돌아옵니다."], at=0.4, dur=4.4, size="l", pos="top")
    tl.advance(5.0)
    rl = 10.5
    tl.beat("reward_layers", rl)
    tl.background("city", rl, dim=0.7, blur=4)
    tl.shot("story", A["turn"] - 1.0, rl, enter="flash")
    tl.words(["깜빡이를 켜고 도는 회전에는,", "바로 보상이 옵니다."], at=0.3, dur=4.8, size="l", pos="top")
    tl.say("n5", at=0.3)
    tl.words(["부드럽게 달릴수록,", "레이어가 하나씩 열립니다."], at=5.6, dur=4.7, size="l", pos="top")
    tl.say("n3", at=5.6)
    tl.advance(rl)
    grace = max(6.5, n("n6") + 0.8)
    tl.beat("grace", grace)
    tl.background("highway", grace, dim=0.7, blur=3)
    tl.shot("story", A["grace"] - 1.6, grace, enter="flash")
    tl.words(["위험을 피한 급조작은 알아봅니다.", "감점은 절반, 숨도 짧게."], at=0.3, dur=grace - 0.5, size="l", pos="top")
    tl.say("n6", at=0.3)
    tl.advance(grace)
    # Scene
    arr = 6.5
    tl.beat("arrival", arr)
    tl.background("arrival", arr, offset=2.0, dim=0.7, blur=2)
    tl.shot("story", A["stop"] - 1.0, arr, style="settle", enter="flash", exit="sink")
    tl.words(["도착하면,", "오늘의 여정이 한 곡으로 남습니다."], at=0.5, dur=arr - 0.7, size="l", pos="top")
    tl.say("n8", at=0.5)
    tl.advance(arr)
    end = 8.0
    tl.beat("end", end)
    rest = max(0.0, min(story["music_end"], story["duration"]) - (A["stop"] - 1.0 + arr))
    tl.music_seg("story", A["stop"] - 1.0 + arr, min(rest, end), tl.t, fade_out=F(2.5))
    tl.card("end", end, title="드라이브 심포니", latin="Drive Symphony", line="주행이 연주가 되는 차", hashtags=True)
    tl.say("n9", at=0.8)
    tl.advance(end)
    return tl.out()


def demo(sources, narr, broll):
    story = sources["story"]; console = sources.get("console"); A = anchors(story)
    tl = Timeline("DemoFull16x9", 1920, 1080, sources, narr, broll)
    # intro
    intro = 10.0
    tl.beat("intro", intro)
    tl.background("hero", intro, offset=0.5, zoom=1.08, dim=0.85, blur=2)
    tl.music_seg("story", max(0.0, A["streak"] - 1.5), intro + 3.5, tl.t, fade_in=F(1.5), fade_out=F(3.0))
    tl.words(["드라이브 심포니"], at=0.6, dur=intro - 0.8, size="title", pos="center")
    tl.words(["주행이 연주가 되는 차"], at=1.6, dur=intro - 1.8, size="m", pos="center2")
    tl.say("n1", at=0.8); tl.say("n2", at=5.3)
    tl.advance(intro)
    tl.beat("chapter1", 3.5)
    tl.card("chapter", 3.5, kicker="시연 1", title="데모 시나리오", line="106초 · 시내 → 습관적 급조작 → 회복 → 고속도로 → 회피 → 비 → 도착 · 앱 자체 음원")
    tl.advance(3.5)
    # story chapter: the whole clean recording, full display
    sd = story["duration"]
    tl.beat("story", sd)
    ph = {p["label"]: p for p in story.get("phases", [])}
    cuts = [(0.0, "city"), (ph.get("고속도로 · 크루즈", {}).get("t0", 45.0), "highway"), (ph.get("블룸", {}).get("t0", 57.5), "window"),
            (ph.get("비 · 와이퍼", {}).get("t0", 91.0), "rain"), (ph.get("제동 · 정차", {}).get("t0", 100.0), "arrival")]
    for k, (t0, role) in enumerate(cuts):
        t1 = cuts[k + 1][0] if k + 1 < len(cuts) else sd
        tl.background(role, t1 - t0, start=tl.t + t0, dim=0.55, blur=5, zoom=1.04)
    tl.shot("story", 0.0, sd, crop="full", style="full", enter="rise", exit="sink")
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
        steps = {c["step"]: c["t0"] for c in cues}
        ccuts = [(0.0, "city"), (steps.get("4.", 19.0), "highway"), (steps.get("9.", 66.0), "rain"), (steps.get("11.", 82.0), "arrival")]
        for k, (t0, role) in enumerate(ccuts):
            t1 = ccuts[k + 1][0] if k + 1 < len(ccuts) else cd
            if t1 > t0: tl.background(role, t1 - t0, start=tl.t + t0, dim=0.55, blur=5, zoom=1.04)
        tl.shot("console", 0.0, cd, crop="full", style="full", enter="rise", exit="sink")
        for c in cues:
            tl.label(c["label"], c["line"], c["t0"], max(2.0, c["t1"] - c["t0"]))
        tl.advance(cd)
    outro = 12.0
    tl.beat("outro", outro)
    tl.music_seg("story", max(0.0, story["duration"] - outro), outro, tl.t, fade_in=F(1.0), fade_out=F(3.0))
    tl.card("outro", outro, title="드라이브 심포니", latin="Drive Symphony", line="주행이 연주가 되는 차",
            features=["조향 · 브레이크 · 기어 · 깜빡이 · 와이퍼 · 크루즈 · 내비 → 실시간 작곡",
                      "안전하지 않은 조작은 음악이 잠시 가라앉을 뿐, 침묵도 꾸중도 없습니다",
                      "Gleo 음성 명령 4종 · 주행 중 글랜스 화면 · 오디오 CPU 5% 미만 · 네트워크 없음"], hashtags=False)
    tl.say("n7", at=0.6); tl.say("n9", at=5.6)
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
    sources = {}
    for name, seg in ev["segments"].items():
        sources[name] = {"video": f"run/{seg['file']}", "audio": f"run/{seg['wav']}" if seg["wav"] else None,
                         "badge_s": seg["badge_s"], "duration": seg["duration"], "music_end": seg["music_end"],
                         "gain_db": sync.get(name, {}).get("gain_db", 0.0), "events": seg["events"], "sags": seg["sags"],
                         "phases": seg.get("phases", []), "cues": seg.get("cues", [])}
    bj = json.load(open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "broll.json"), encoding="utf-8"))
    clips = {}; order = []
    for c in bj["clips"]:
        p = os.path.join(pub, "broll", f"{c['id']}.mp4")
        if os.path.exists(p) and os.path.getsize(p) > 1_000_000:
            clips[c["id"]] = {"file": f"broll/{c['id']}.mp4", "duration": round(probe(p), 3), "roles": c["roles"]}; order.append(c["id"])
    if not clips: raise SystemExit("no b-roll in public/broll (run tools/broll.py)")
    broll = {"clips": clips, "order": order}
    plan = {"fps": FPS, "sources": sources, "narration": {k: {"file": f"run/{v['file']}", "duration": v["duration"], "text": v["text"]} for k, v in narr.items()},
            "voice": nj.get("voice"), "broll": clips, "hashtags": HASHTAGS,
            "noise": "gen/noise.png" if os.path.exists(os.path.join(pub, "gen", "noise.png")) else None,
            "footer": "공모전 출품작 · 에뮬레이터 화면 녹화와 앱 자체 음원 · 주행 영상은 스톡 푸티지 · 출시 전",
            "compositions": {"Ad16x9": ad(sources, narr, broll), "Short9x16": short(sources, narr, broll), "DemoFull16x9": demo(sources, narr, broll)}}
    json.dump(plan, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    for k, c in plan["compositions"].items():
        print(f"{k}: {c['duration'] / FPS:.1f} s, {len(c['panel'])} shots, {len(c['callouts'])} call-outs, {len(c['voice'])} voice lines, {len(c['music'])} music segments")
        print("   beats: " + ", ".join(f"{b['id']}@{b['start'] / FPS:.1f}" for b in c["beats"]))


if __name__ == "__main__":
    main()
