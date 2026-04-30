/*
 * VRM-powered 3D avatar for Tour Avatar.
 *
 * Loads `model.vrm` via three-vrm (Apache-2.0). Drives expressions,
 * lipsync, blinking, and subtle idle motion based on commands from
 * the Kotlin side (TourAvatar.setEmotion / setSpeaking / wave).
 *
 * If model.vrm fails to load (missing or incompatible), falls back to
 * a small procedural placeholder so the UI is never empty.
 */

import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from 'three-vrm';

const stage    = document.getElementById('stage');
const badge    = document.getElementById('badge');
const badgeTx  = document.getElementById('badgeText');

const scene    = new THREE.Scene();
const camera   = new THREE.PerspectiveCamera(28, 1, 0.1, 100);
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.setSize(stage.clientWidth, stage.clientHeight);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
stage.appendChild(renderer.domElement);

// Soft warm museum lighting
scene.add(new THREE.HemisphereLight(0xfff0d7, 0x2c2522, 0.85));
const key = new THREE.DirectionalLight(0xffe9c4, 1.05);
key.position.set(2.5, 4, 3);
scene.add(key);
const rim = new THREE.DirectionalLight(0xb8956a, 0.5);
rim.position.set(-3, 2, -2);
scene.add(rim);

// Subtle stage plate so the model isn't floating in the void
{
    const plate = new THREE.Mesh(
        new THREE.CylinderGeometry(0.55, 0.55, 0.04, 32),
        new THREE.MeshStandardMaterial({ color: 0x3a2d22, roughness: 0.85 }),
    );
    plate.position.y = -0.02;
    scene.add(plate);
}

// ─── Avatar load ────────────────────────────────────────────────
let vrm = null;
let mixer = null;     // reserved for future BVH animation playback
const clock = new THREE.Clock();

const loader = new GLTFLoader();
loader.register((parser) => new VRMLoaderPlugin(parser));

loader.load(
    'model.vrm',
    (gltf) => {
        vrm = gltf.userData.vrm;
        VRMUtils.removeUnnecessaryVertices(gltf.scene);
        VRMUtils.removeUnnecessaryJoints(gltf.scene);

        // VRM 0.x faces +Z; VRM 1.0 faces -Z. Either way we want the avatar
        // to look at the camera, so rotate to face +Z (toward viewer).
        if (vrm.meta && vrm.meta.metaVersion === '0') {
            VRMUtils.rotateVRM0(vrm);
        }
        scene.add(vrm.scene);

        // Frame the upper body. VRM scale is meters; head height ~ 1.45m.
        const head = vrm.humanoid?.getNormalizedBoneNode?.('head');
        const headY = head ? head.getWorldPosition(new THREE.Vector3()).y : 1.45;
        camera.position.set(0, headY - 0.1, 1.7);
        camera.lookAt(0, headY - 0.05, 0);

        // Relax the arms a little (defaults are T-pose). Brings arms down ~30°.
        relaxArms(vrm);

        // Initial expression
        applyEmotion('idle');

        try { window.TourAvatarBridge?.onAvatarReady?.(); } catch (_) {}
    },
    undefined,
    (err) => {
        console.warn('[avatar] failed to load model.vrm:', err);
        try { window.TourAvatarBridge?.onAvatarError?.(String(err)); } catch (_) {}
        // Show a clear visual fallback so the screen isn't blank.
        scene.add(buildFallback());
        camera.position.set(0, 1.45, 3.6);
        camera.lookAt(0, 1.3, 0);
    },
);

function relaxArms(vrm) {
    const armDownDeg = 65;   // 0 = T-pose, 90 = arms straight down
    const r = THREE.MathUtils.degToRad(armDownDeg);
    for (const side of ['left', 'right']) {
        const upper = vrm.humanoid?.getNormalizedBoneNode?.(side + 'UpperArm');
        if (upper) {
            // VRM upper-arm bone: rotate around Z to bring arm down at side
            upper.rotation.z = (side === 'left' ? +1 : -1) * r;
        }
    }
}

function buildFallback() {
    const g = new THREE.Group();
    const mat = new THREE.MeshStandardMaterial({ color: 0xb8956a });
    const body = new THREE.Mesh(new THREE.CapsuleGeometry(0.32, 0.55, 6, 16), mat);
    body.position.y = 0.95;
    g.add(body);
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.28, 32, 32),
        new THREE.MeshStandardMaterial({ color: 0xf2d5b0 }));
    head.position.y = 1.65;
    g.add(head);
    return g;
}

// ─── Expression / emotion mapping ──────────────────────────────
//
// VRM 1.0 standard expressions: happy, sad, angry, surprised, relaxed,
// neutral, blink, blinkLeft, blinkRight, aa, ih, ou, ee, oh, lookUp,
// lookDown, lookLeft, lookRight.
// Not all VRMs implement all of them — apply gracefully.

function applyEmotion(name) {
    if (!vrm?.expressionManager) return;
    const em = vrm.expressionManager;
    const all = ['happy', 'sad', 'angry', 'surprised', 'relaxed', 'neutral'];
    all.forEach((e) => em.setValue(e, 0));
    switch (name) {
        case 'idle':
            em.setValue('relaxed', 0.15);
            break;
        case 'listening':
            em.setValue('happy', 0.35);
            break;
        case 'thinking':
            em.setValue('relaxed', 0.5);
            break;
        case 'speaking':
            em.setValue('happy', 0.25);
            break;
        case 'error':
            em.setValue('sad', 0.6);
            break;
    }
    em.update();
}

// ─── State machine ────────────────────────────────────────────────
let state    = 'idle';
let speaking = false;
let waving   = 0;
let lookYaw  = 0;        // smoothed head yaw target

window.TourAvatar = {
    setEmotion(name) {
        state = name;
        badge.className = name;
        badgeTx.textContent = ({
            idle: '就绪', listening: '聆听', thinking: '思考', speaking: '回答', error: '出错',
        })[name] || name;
        applyEmotion(name);
    },
    setSpeaking(active) { speaking = !!active; },
    wave() { waving = 1.6; },
};

// ─── Render loop ──────────────────────────────────────────────────
const mouthShapes = ['aa', 'ih', 'ou', 'ee', 'oh'];
let mouthPhase = 0;
let blinkPhase = 0;
let nextBlinkAt = 2.0;

function tick() {
    const dt = clock.getDelta();
    const t  = clock.elapsedTime;

    if (vrm) {
        const em = vrm.expressionManager;
        const head = vrm.humanoid?.getNormalizedBoneNode?.('head');

        // Idle subtle motion
        if (head) {
            // small head sway driven by emotion
            let targetPitch = 0, targetYaw = 0, targetRoll = 0;
            switch (state) {
                case 'listening':
                    targetPitch = -0.10 + Math.sin(t * 1.2) * 0.02;
                    break;
                case 'thinking':
                    targetPitch = 0.07;
                    targetYaw   = 0.18 + Math.sin(t * 0.8) * 0.04;
                    break;
                case 'error':
                    targetRoll = Math.sin(t * 6) * 0.07;
                    break;
                default:
                    targetYaw   = Math.sin(t * 0.6) * 0.10;
                    targetPitch = Math.sin(t * 0.4) * 0.04;
            }
            head.rotation.x += (targetPitch - head.rotation.x) * 0.08;
            head.rotation.y += (targetYaw   - head.rotation.y) * 0.08;
            head.rotation.z += (targetRoll  - head.rotation.z) * 0.08;
        }

        // Blinking — every 2-5 seconds
        if (em) {
            blinkPhase += dt;
            if (blinkPhase < 0.16) {
                em.setValue('blink', Math.sin((blinkPhase / 0.16) * Math.PI));
            } else if (blinkPhase >= nextBlinkAt) {
                blinkPhase = 0;
                nextBlinkAt = 2.0 + Math.random() * 3.0;
            } else {
                em.setValue('blink', 0);
            }
        }

        // Lipsync — fake phoneme cycling while speaking
        if (em) {
            mouthShapes.forEach((m) => em.setValue(m, 0));
            if (speaking) {
                mouthPhase += dt * 9;            // ~9 mouth shapes/sec
                const shape = mouthShapes[Math.floor(mouthPhase) % mouthShapes.length];
                const intensity = 0.55 + Math.sin(mouthPhase * Math.PI) * 0.25;
                em.setValue(shape, Math.max(0, intensity));
            } else {
                mouthPhase = 0;
            }
        }

        // VRM internal update (springs, lookAt, etc.)
        if (em) em.update();
        vrm.update(dt);
    }

    // Wave (one-shot) — rotate right upper arm
    if (waving > 0 && vrm?.humanoid) {
        waving -= dt;
        const phase = (1.6 - waving) * 6;
        const upper = vrm.humanoid.getNormalizedBoneNode('rightUpperArm');
        const lower = vrm.humanoid.getNormalizedBoneNode('rightLowerArm');
        if (upper) upper.rotation.z = -1.5 + Math.sin(phase) * 0.5;
        if (lower) lower.rotation.z = -0.5 + Math.sin(phase * 1.6) * 0.3;
        if (waving <= 0 && vrm) relaxArms(vrm);
    }

    renderer.render(scene, camera);
    requestAnimationFrame(tick);
}
tick();

// ─── Resize handling ──────────────────────────────────────────────
function resize() {
    const w = stage.clientWidth, h = stage.clientHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);
}
new ResizeObserver(resize).observe(stage);
resize();

// Default emotion (also re-applied once VRM finishes loading)
TourAvatar.setEmotion('idle');
