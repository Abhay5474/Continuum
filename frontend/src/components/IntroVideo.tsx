import { useCallback, useEffect, useRef, useState } from "react";

/**
 * The narrated introduction.
 *
 * <p>Click to play, never autoplay. A narrated video that starts on its own is
 * either muted — in which case the narration, the entire point, is lost — or it
 * talks at someone who did not ask, which is the single most disliked thing a
 * landing page can do. It also would not survive a browser's autoplay policy.
 *
 * <p>The captions are burned into the picture rather than layered as a text
 * track, because most of the people who watch this will do so with the sound
 * off and a track that a player might not render is not a caption.
 *
 * <p>A transcript sits under the player. It is the accessible form, and it is
 * also the only form a search engine or a reader in a hurry can use.
 */

/**
 * Chapters, timings and transcript come from the build, not from this file.
 *
 * <p>They were typed in here once. Then the narration was rewritten, every
 * marker silently pointed at the wrong second, and nothing failed — which is
 * the worst kind of wrong. `video/build.mjs` now emits this from the measured
 * audio, so a rewritten line moves the chapters with it.
 */
import intro from "../generated/intro-chapters.json";

const CHAPTERS = intro.chapters;
const TOTAL = intro.total;

export default function IntroVideo() {
  const video = useRef<HTMLVideoElement | null>(null);
  const [started, setStarted] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [t, setT] = useState(0);
  const [duration, setDuration] = useState(TOTAL);
  const [transcript, setTranscript] = useState(false);

  const play = useCallback(() => {
    const el = video.current;
    if (!el) return;
    setStarted(true);
    void el.play();
  }, []);

  const toggle = useCallback(() => {
    const el = video.current;
    if (!el) return;
    if (el.paused) {
      setStarted(true);
      void el.play();
    } else {
      el.pause();
    }
  }, []);

  useEffect(() => {
    const el = video.current;
    if (!el) return;
    const onTime = () => setT(el.currentTime);
    const onMeta = () => setDuration(el.duration || TOTAL);
    const onPlay = () => setPlaying(true);
    const onPause = () => setPlaying(false);
    el.addEventListener("timeupdate", onTime);
    el.addEventListener("loadedmetadata", onMeta);
    el.addEventListener("play", onPlay);
    el.addEventListener("pause", onPause);
    return () => {
      el.removeEventListener("timeupdate", onTime);
      el.removeEventListener("loadedmetadata", onMeta);
      el.removeEventListener("play", onPlay);
      el.removeEventListener("pause", onPause);
    };
  }, []);

  const seek = (at: number) => {
    const el = video.current;
    if (!el) return;
    el.currentTime = at;
    setStarted(true);
    void el.play();
  };

  const active = CHAPTERS.reduce((acc, c, i) => (t >= c.at ? i : acc), 0);
  const pct = duration > 0 ? (t / duration) * 100 : 0;

  return (
    <div className="mx-auto w-full max-w-4xl text-left">
      <div className="group relative overflow-hidden rounded-xl border border-edge bg-panel/60 shadow-[0_24px_80px_-32px_rgba(0,0,0,0.9)] backdrop-blur-sm">
        <video
          ref={video}
          className="block w-full"
          poster="/continuum-intro-poster.jpg"
          playsInline
          preload="metadata"
          onClick={toggle}
        >
          <source src="/continuum-intro.mp4" type="video/mp4" />
          Your browser cannot play this video. The transcript below says the same thing.
        </video>

        {/* One large target before the first play, a small one after. A control
            bar that never goes away covers the picture it is controlling. */}
        {!started && (
          <button
            onClick={play}
            aria-label={`Play the introduction, ${Math.round(TOTAL)} seconds, with narration`}
            /* A scrim centred behind the label rather than a flat wash over
               the whole frame: the poster stays legible at the edges and the
               overlay text is not fighting the picture underneath it. */
            className="absolute inset-0 grid place-items-center transition-opacity duration-300 hover:opacity-90"
            style={{
              background:
                "radial-gradient(circle at 50% 52%, rgba(6,8,12,0.94) 0%, rgba(6,8,12,0.82) 26%, rgba(6,8,12,0.34) 58%, rgba(6,8,12,0.62) 100%)",
            }}
          >
            <span className="flex flex-col items-center gap-3">
              <span className="grid h-16 w-16 place-items-center rounded-full bg-aurora/90 shadow-[0_0_44px_rgba(76,139,245,0.5)] transition-transform duration-500 ease-out group-hover:scale-105">
                <svg width="22" height="24" viewBox="0 0 22 24" fill="none" aria-hidden>
                  <path d="M3 2.5 19.5 12 3 21.5z" fill="#06080c" />
                </svg>
              </span>
              <span className="text-sm font-medium text-slate-100">Watch the introduction</span>
              <span className="text-xs text-slate-400">
                {Math.round(TOTAL)} seconds · narrated · captions on screen
              </span>
            </span>
          </button>
        )}

        {started && (
          <div className="pointer-events-none absolute inset-x-0 bottom-0 opacity-0 transition-opacity duration-300 group-hover:opacity-100 focus-within:opacity-100">
            <div className="pointer-events-auto flex items-center gap-3 bg-gradient-to-t from-ink/95 to-transparent px-4 pb-3 pt-8">
              <button
                onClick={toggle}
                aria-label={playing ? "Pause" : "Play"}
                className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-slate-100/90 text-ink"
              >
                {playing ? (
                  <svg width="10" height="12" viewBox="0 0 10 12" aria-hidden>
                    <rect width="3" height="12" fill="currentColor" />
                    <rect x="7" width="3" height="12" fill="currentColor" />
                  </svg>
                ) : (
                  <svg width="11" height="12" viewBox="0 0 11 12" aria-hidden>
                    <path d="M1 1 10 6 1 11z" fill="currentColor" />
                  </svg>
                )}
              </button>
              <div className="h-[3px] flex-1 overflow-hidden rounded-full bg-white/15">
                <div
                  className="h-full rounded-full bg-aurora transition-[width] duration-200 ease-linear"
                  style={{ width: `${pct}%` }}
                />
              </div>
              <span className="readout w-16 shrink-0 text-right text-[11px] tabular-nums text-slate-300">
                {fmtTime(t)} / {fmtTime(duration)}
              </span>
            </div>
          </div>
        )}
      </div>

      {/* Chapters, so someone who only wants the context layer can go straight
          to it rather than watching seventy nine seconds to find it. */}
      <div className="mt-3 flex flex-wrap gap-1.5">
        {CHAPTERS.map((c, i) => (
          <button
            key={c.at}
            onClick={() => seek(c.at)}
            className={`rounded-md border px-2.5 py-1 text-xs transition-colors ${
              started && i === active
                ? "border-aurora/60 bg-aurora/10 text-slate-100"
                : "border-edge/70 text-slate-500 hover:border-aurora/40 hover:text-slate-300"
            }`}
          >
            {c.label}
          </button>
        ))}
      </div>

      <div className="mt-3">
        <button
          onClick={() => setTranscript(!transcript)}
          aria-expanded={transcript}
          className="text-xs text-slate-500 underline decoration-dotted underline-offset-4 transition-colors hover:text-slate-300"
        >
          {transcript ? "Hide transcript" : "Read the transcript instead"}
        </button>
        {transcript && (
          <div className="mt-3 space-y-2.5 rounded-lg border border-edge/70 bg-panel/40 p-4 text-left">
            {CHAPTERS.map((c) => (
              <p key={c.id} className="pl-12 -indent-12 text-sm leading-relaxed text-slate-400">
                <button
                  onClick={() => seek(c.at)}
                  className="mr-2 text-[11px] text-slate-600 transition-colors hover:text-aurora"
                >
                  {fmtTime(c.at)}
                </button>
                {c.text}
              </p>
            ))}
            <p className="pt-1 text-xs text-slate-600">
              The narration is speech-synthesised. The captions in the picture are the same words,
              so the video works with the sound off.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

function fmtTime(s: number) {
  if (!Number.isFinite(s)) return "0:00";
  const m = Math.floor(s / 60);
  return `${m}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
}
