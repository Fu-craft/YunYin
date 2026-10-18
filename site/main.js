'use strict';
/* ============================================================================
   云音 YunYin — landing page behaviour
   No dependencies. Motion is transform/opacity only; every decorative effect is
   gated on prefers-reduced-motion, and the page is complete without JS.
   ========================================================================== */

(function () {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const finePointer = window.matchMedia('(hover: hover) and (pointer: fine)');

  function on(el, type, fn, opts) {
    if (el) el.addEventListener(type, fn, opts);
  }

  /* ---------------------------------------------------------------- screenshots
     Fade each real capture in over its 20px-wide LQIP stand-in. If an image is
     already cached (or fails), settle immediately so no frame is ever empty. */
  document.body.classList.add('js-lqip');
  document.querySelectorAll('.shot').forEach(function (img) {
    function settle() { img.classList.add('is-loaded'); }
    if (img.complete && img.naturalWidth > 0) settle();
    else {
      on(img, 'load', settle, { once: true });
      on(img, 'error', settle, { once: true });
    }
  });

  /* ---------------------------------------------------------------- scroll
     One passive listener queues one rAF; the rAF writes (progress line, nav
     glass, field drift) and never reads layout. */
  const nav = document.querySelector('[data-nav]');
  const field = document.querySelector('.field');
  const progress = document.querySelector('.scroll-progress');
  let scrollQueued = false;
  let lastY = -1;

  function paintScroll() {
    scrollQueued = false;
    const y = window.scrollY || window.pageYOffset || 0;
    if (y === lastY) return;
    lastY = y;
    const max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    const p = Math.min(1, Math.max(0, y / max));
    if (progress) progress.style.transform = 'scaleX(' + p.toFixed(4) + ')';
    if (nav) nav.classList.toggle('is-scrolled', y > 12);
    /* the field breathes with the page — decorative, so reduced motion drops it in CSS */
    if (field) field.style.setProperty('--field-shift', (-y * 0.05).toFixed(1) + 'px');
  }
  function queueScroll() {
    if (scrollQueued) return;
    scrollQueued = true;
    requestAnimationFrame(paintScroll);
  }
  on(window, 'scroll', queueScroll, { passive: true });
  on(window, 'resize', queueScroll, { passive: true });
  paintScroll();

  /* ---------------------------------------------------------------- reveals
     Opacity + 16px rise, once per block, 70ms stagger via --i in the markup. */
  const reveals = Array.prototype.slice.call(document.querySelectorAll('.reveal'));
  if ('IntersectionObserver' in window && !reduceMotion.matches) {
    const io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-in');
        io.unobserve(entry.target);
      });
    }, { threshold: 0.12, rootMargin: '0px 0px -6% 0px' });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add('is-in'); });
  }

  /* ---------------------------------------------------------------- hero tilt
     Pointer-tracked, interruptible, springs back on leave. Desktop pointers only,
     and never under reduced motion. All writes are transform/custom-property. */
  const tilt = document.querySelector('[data-tilt]');
  const tiltStage = tilt ? tilt.querySelector('[data-tilt-stage]') : null;
  if (tilt && tiltStage && finePointer.matches && !reduceMotion.matches) {
    const MAX_DEG = 5;
    let targetX = 0;
    let targetY = 0;
    let curX = 0;
    let curY = 0;
    let rafId = 0;
    let rect = { left: 0, top: 0, width: 1, height: 1 };

    function tick() {
      curX += (targetX - curX) * 0.12;
      curY += (targetY - curY) * 0.12;
      tiltStage.style.transform =
        'perspective(1400px) rotateX(' + (-curY).toFixed(3) + 'deg) rotateY(' + curX.toFixed(3) + 'deg)';
      if (Math.abs(targetX - curX) > 0.02 || Math.abs(targetY - curY) > 0.02) {
        rafId = requestAnimationFrame(tick);
      } else {
        rafId = 0;
      }
    }
    function startTilt() {
      if (!rafId) rafId = requestAnimationFrame(tick);
    }
    on(tilt, 'pointerenter', function () { rect = tilt.getBoundingClientRect(); }, { passive: true });
    on(tilt, 'pointermove', function (e) {
      const nx = (e.clientX - rect.left) / rect.width - 0.5;
      const ny = (e.clientY - rect.top) / rect.height - 0.5;
      targetX = nx * MAX_DEG * 2;
      targetY = ny * MAX_DEG * 2;
      tilt.style.setProperty('--sheen-x', ((nx + 0.5) * 100).toFixed(1) + '%');
      tilt.style.setProperty('--sheen-y', ((ny + 0.5) * 100).toFixed(1) + '%');
      startTilt();
    }, { passive: true });
    on(tilt, 'pointerleave', function () { targetX = 0; targetY = 0; startTilt(); }, { passive: true });
  }

  /* ---------------------------------------------------------------- karaoke
     Word-by-word light-up, once when scrolled into view, replayable. Under
     reduced motion the CSS freezes a readable static frame and the button hides. */
  const karaoke = document.querySelector('[data-karaoke]');
  if (karaoke) {
    const words = Array.prototype.slice.call(karaoke.querySelectorAll('.w'));
    const replay = karaoke.querySelector('[data-karaoke-replay]');
    const STEP = 148;
    const TAIL = 700;
    let rafId = 0;
    let startedAt = 0;
    let lit = 0;
    let played = false;

    function resetKaraoke() {
      cancelAnimationFrame(rafId);
      rafId = 0;
      startedAt = 0;
      lit = 0;
      karaoke.classList.remove('is-done');
      words.forEach(function (w) { w.classList.remove('is-lit', 'is-current'); });
    }
    function frame(now) {
      if (!startedAt) startedAt = now;
      const elapsed = now - startedAt;
      const want = Math.min(words.length, Math.floor(elapsed / STEP) + 1);
      while (lit < want) {
        if (lit > 0) words[lit - 1].classList.remove('is-current');
        words[lit].classList.add('is-lit', 'is-current');
        lit += 1;
      }
      if (elapsed < words.length * STEP + TAIL) {
        rafId = requestAnimationFrame(frame);
      } else {
        if (lit > 0) words[lit - 1].classList.remove('is-current');
        karaoke.classList.add('is-done');
        rafId = 0;
      }
    }
    function playKaraoke() {
      if (reduceMotion.matches) return;
      resetKaraoke();
      rafId = requestAnimationFrame(frame);
    }

    if (!reduceMotion.matches) {
      if ('IntersectionObserver' in window) {
        const io = new IntersectionObserver(function (entries) {
          entries.forEach(function (entry) {
            if (!entry.isIntersecting || played) return;
            played = true;
            playKaraoke();
            io.disconnect();
          });
        }, { threshold: 0.35 });
        io.observe(karaoke);
      } else {
        playKaraoke();
      }
      on(replay, 'click', playKaraoke);
    }
  }

  /* ---------------------------------------------------------------- glass bar demo
     A faithful DOM copy of the app's two-slot bar: 1:1 drag, velocity handoff,
     and Apple's damping-0.8 / response-~0.3s spring. Selection is a real tablist,
     so arrows / Home / End work and the pill is decorative only. */
  const bar = document.querySelector('.bbar');
  if (bar) {
    const tablist = bar.querySelector('[role="tablist"]');
    const tabsBox = bar.querySelector('.bbar__tabs');
    const pill = bar.querySelector('.bbar__pill');
    const tabs = Array.prototype.slice.call(bar.querySelectorAll('[role="tab"]'));
    const STIFFNESS = 420;
    const DAMPING = 0.8;
    const DAMPING_C = 2 * DAMPING * Math.sqrt(STIFFNESS);

    let selected = 0;
    let span = 56;
    let pos = 0;
    let springId = 0;
    let dragging = false;
    let dragMoved = false;
    let pointerId = null;
    let dragStartX = 0;
    let dragStartPos = 0;
    let samples = [];

    function paintPill() {
      if (!pill) return;
      pill.style.setProperty('--pill-x', pos.toFixed(2) + 'px');
    }
    function markSelected() {
      tabs.forEach(function (tab, i) {
        tab.classList.toggle('is-selected', i === selected);
        tab.setAttribute('aria-selected', i === selected ? 'true' : 'false');
        tab.tabIndex = i === selected ? 0 : -1;
      });
    }
    function measureBar() {
      if (!tabsBox || !pill) return;
      span = Math.max(0, tabsBox.clientWidth - pill.getBoundingClientRect().width);
      if (!dragging && !springId) {
        pos = selected * span;
        paintPill();
      }
    }
    function stopSpring() {
      cancelAnimationFrame(springId);
      springId = 0;
    }
    function springTo(target, initialVelocity) {
      stopSpring();
      let x = pos;
      let v = initialVelocity || 0;
      let last = performance.now();
      function step(now) {
        const dt = Math.min(0.032, Math.max(0.001, (now - last) / 1000));
        last = now;
        const accel = -STIFFNESS * (x - target) - DAMPING_C * v;
        v += accel * dt;
        x += v * dt;
        if (Math.abs(x - target) < 0.4 && Math.abs(v) < 12) {
          pos = target;
          paintPill();
          springId = 0;
          return;
        }
        pos = x;
        paintPill();
        springId = requestAnimationFrame(step);
      }
      springId = requestAnimationFrame(step);
    }
    function snapTo(target) {
      stopSpring();
      pos = target;
      paintPill();
    }
    function select(index) {
      selected = index;
      markSelected();
      const target = index * span;
      if (reduceMotion.matches) snapTo(target);
      else springTo(target, 0);
    }
    function commitDragVelocity(velocity) {
      const target = Math.abs(velocity) > 320 ? (velocity > 0 ? span : 0) : (pos < span / 2 ? 0 : span);
      selected = target === 0 ? 0 : 1;
      markSelected();
      if (reduceMotion.matches) snapTo(target);
      else springTo(target, velocity);
    }

    if (tabsBox && tablist && pill) {
      on(tabsBox, 'pointerdown', function (e) {
        if (e.button !== undefined && e.button !== 0) return;
        dragging = true;
        dragMoved = false;
        pointerId = e.pointerId;
        dragStartX = e.clientX;
        dragStartPos = pos;
        samples = [{ t: e.timeStamp, x: pos }];
        stopSpring();
        if (tabsBox.setPointerCapture) {
          try { tabsBox.setPointerCapture(e.pointerId); } catch (err) { /* older browsers */ }
        }
      });
      on(tabsBox, 'pointermove', function (e) {
        if (!dragging || e.pointerId !== pointerId) return;
        const dx = e.clientX - dragStartX;
        if (Math.abs(dx) > 3) dragMoved = true;
        pos = Math.min(span, Math.max(0, dragStartPos + dx));
        paintPill();
        samples.push({ t: e.timeStamp, x: pos });
        if (samples.length > 8) samples.shift();
      }, { passive: true });
      on(tabsBox, 'pointerup', function (e) {
        if (!dragging || e.pointerId !== pointerId) return;
        dragging = false;
        let velocity = 0;
        if (samples.length > 1) {
          const first = samples[0];
          const last = samples[samples.length - 1];
          const dt = last.t - first.t;
          if (dt > 0) velocity = ((last.x - first.x) / dt) * 1000;
        }
        commitDragVelocity(velocity);
      });
      on(tabsBox, 'pointercancel', function () {
        if (!dragging) return;
        dragging = false;
        commitDragVelocity(0);
      });

      on(tablist, 'click', function (e) {
        const tab = e.target.closest ? e.target.closest('[role="tab"]') : null;
        if (!tab) return;
        if (dragMoved) { dragMoved = false; return; }
        const index = Number(tab.getAttribute('data-index'));
        if (!isNaN(index)) select(index);
      });
      on(tablist, 'keydown', function (e) {
        const key = e.key;
        let next = -1;
        if (key === 'ArrowRight' || key === 'ArrowDown') next = Math.min(tabs.length - 1, selected + 1);
        else if (key === 'ArrowLeft' || key === 'ArrowUp') next = Math.max(0, selected - 1);
        else if (key === 'Home') next = 0;
        else if (key === 'End') next = tabs.length - 1;
        else return;
        e.preventDefault();
        select(next);
        tabs[next].focus();
      });

      measureBar();
      on(window, 'resize', measureBar, { passive: true });
      if ('ResizeObserver' in window) new ResizeObserver(measureBar).observe(tabsBox);
      on(reduceMotion, 'change', function () {
        if (!reduceMotion.matches) return;
        snapTo(selected * span);
      });
    }
  }
})();
