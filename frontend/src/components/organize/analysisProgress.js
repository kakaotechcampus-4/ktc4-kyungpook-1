// Background analysis progress store.
//
// AnalyzingStep's timer used to live entirely inside the component's own
// useEffect, so unmounting the component (e.g. navigating away with
// "다른 작업 하러 가기") cancelled the timer and lost all progress, even
// though the UI claims the analysis keeps running in the background.
//
// This module keeps the progress at module scope instead of component
// state, so the timer keeps ticking regardless of which component (if any)
// is currently mounted, and any component can re-attach to read the
// current progress.

const STEP_MS = 900;

let state = null; // { repoId, totalSteps, doneCount, finished, timer, listeners }

function notify() {
  if (!state) return;
  state.listeners.forEach((listener) => listener(state.doneCount, state.finished));
}

function scheduleNext() {
  state.timer = setTimeout(() => {
    state.doneCount += 1;
    if (state.doneCount >= state.totalSteps) {
      state.finished = true;
      notify();
      return;
    }
    notify();
    scheduleNext();
  }, STEP_MS);
}

export function startAnalysis(repoId, totalSteps) {
  if (state && state.repoId === repoId) return;
  if (state?.timer) clearTimeout(state.timer);
  state = { repoId, totalSteps, doneCount: 0, finished: false, timer: null, listeners: new Set() };
  scheduleNext();
}

export function getProgress(repoId) {
  if (!state || state.repoId !== repoId) return null;
  return { doneCount: state.doneCount, finished: state.finished };
}

export function getActiveAnalysis() {
  if (!state) return null;
  return { repoId: state.repoId, finished: state.finished };
}

export function subscribeToAnalysis(repoId, listener) {
  if (!state || state.repoId !== repoId) return () => {};
  // Capture this specific state object rather than closing over the mutable
  // module-level `state` binding. If the analysis finishes (or is cleared)
  // before this listener unsubscribes, `state` may already be null or point
  // at a newer analysis by the time the cleanup runs — reading `state.listeners`
  // then would throw. The captured object stays valid (and its Set harmless
  // to mutate) even after the module has moved on.
  const target = state;
  target.listeners.add(listener);
  return () => target.listeners.delete(listener);
}

export function clearAnalysis(repoId) {
  if (state && state.repoId === repoId) {
    if (state.timer) clearTimeout(state.timer);
    state = null;
  }
}
