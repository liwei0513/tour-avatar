/*
 * Procedural 3D avatar for Tour Avatar.
 *
 * The MVP renders a stylized geometric guide (head + body + arms) with smooth
 * idle / listen / think / speak / wave animations. To replace this with a
 * real VRM model:
 *
 *   1. Drop your model.vrm into app/src/main/assets/avatar/
 *   2. Uncomment the VRM loader block below
 *   3. Comment out `buildProceduralAvatar()`
 *
 * The bridge contract (called from Kotlin via TourAvatar.* in WebView):
 *   - setEmotion(name)   name in {idle, listening, thinking, speaking, error}
 *   - setSpeaking(bool)  toggles mouth animation
 *   - wave()             one-shot greeting animation
 */

import * as THREE from 'three';
// import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
// import { VRMLoaderPlugin, VRMUtils } from 'three-vrm';

const stage    = document.getElementById('stage');
const badge    = document.getElementById('badge');
const badgeTx  = document.getElementById('badgeText');

const scene    = new THREE.Scene();
const camera   = new THREE.PerspectiveCamera(35, 1, 0.1, 100);
camera.position.set(0, 1.45, 3.6);
camera.lookAt(0, 1.3, 0);

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.setSize(stage.clientWidth, stage.clientHeight);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
stage.appendChild(renderer.domElement);

// Soft warm lighting reminiscent of museum lighting
scene.add(new THREE.HemisphereLight(0xfff0d7, 0x2c2522, 0.7));
const key = new THREE.DirectionalLight(0xffe9c4, 0.9);
key.position.set(2.5, 4, 3);
scene.add(key);
const rim = new THREE.DirectionalLight(0xb8956a, 0.5);
rim.position.set(-3, 2, -2);
scene.add(rim);

// ─── Procedural avatar ────────────────────────────────────────────────
const avatar = buildProceduralAvatar();
scene.add(avatar.root);

function buildProceduralAvatar() {
    const root = new THREE.Group();

    // Body — tapered capsule, museum-guide robe palette
    const bodyMat = new THREE.MeshStandardMaterial({
        color: 0xb8956a, roughness: 0.65, metalness: 0.05,
    });
    const body = new THREE.Mesh(
        new THREE.CapsuleGeometry(0.32, 0.55, 6, 16),
        bodyMat,
    );
    body.position.y = 0.95;
    root.add(body);

    // Collar / scarf accent
    const scarfMat = new THREE.MeshStandardMaterial({
        color: 0x7c5e3f, roughness: 0.5,
    });
    const scarf = new THREE.Mesh(
        new THREE.TorusGeometry(0.27, 0.06, 8, 24),
        scarfMat,
    );
    scarf.position.y = 1.35;
    scarf.rotation.x = Math.PI / 2;
    root.add(scarf);

    // Head
    const skinMat = new THREE.MeshStandardMaterial({
        color: 0xf2d5b0, roughness: 0.55,
    });
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.28, 32, 32), skinMat);
    head.position.y = 1.65;
    root.add(head);

    // Eyes
    const eyeMat = new THREE.MeshStandardMaterial({ color: 0x1a1614, roughness: 0.3 });
    const leftEye = new THREE.Mesh(new THREE.SphereGeometry(0.035, 16, 16), eyeMat);
    leftEye.position.set(-0.09, 1.7, 0.245);
    const rightEye = leftEye.clone();
    rightEye.position.x = 0.09;
    root.add(leftEye, rightEye);

    // Mouth — small flattened sphere, scaled vertically while speaking
    const mouthMat = new THREE.MeshStandardMaterial({ color: 0x7c2d2a, roughness: 0.4 });
    const mouth = new THREE.Mesh(new THREE.SphereGeometry(0.05, 16, 16), mouthMat);
    mouth.position.set(0, 1.58, 0.255);
    mouth.scale.set(1.1, 0.25, 0.4);
    root.add(mouth);

    // Arms — simple pill shapes
    const armMat = bodyMat.clone();
    const leftArm = new THREE.Mesh(
        new THREE.CapsuleGeometry(0.075, 0.5, 4, 8), armMat,
    );
    leftArm.position.set(-0.39, 0.95, 0);
    leftArm.rotation.z = 0.18;
    root.add(leftArm);
    const rightArm = leftArm.clone();
    rightArm.position.x = 0.39;
    rightArm.rotation.z = -0.18;
    root.add(rightArm);

    // Subtle floor plate, hints at a stage
    const plateMat = new THREE.MeshStandardMaterial({
        color: 0x3a2d22, roughness: 0.85,
    });
    const plate = new THREE.Mesh(new THREE.CylinderGeometry(0.55, 0.55, 0.04, 32), plateMat);
    plate.position.y = 0.42;
    root.add(plate);

    return { root, body, head, mouth, leftArm, rightArm, scarf };
}

/* To plug in a real .vrm model, replace buildProceduralAvatar() with:

const loader = new GLTFLoader();
loader.register((parser) => new VRMLoaderPlugin(parser));
loader.load('model.vrm', (gltf) => {
    const vrm = gltf.userData.vrm;
    VRMUtils.removeUnnecessaryVertices(gltf.scene);
    VRMUtils.removeUnnecessaryJoints(gltf.scene);
    scene.add(vrm.scene);
    avatar.vrm = vrm;
});
*/

// ─── State machine ────────────────────────────────────────────────────
let state = 'idle';        // 'idle' | 'listening' | 'thinking' | 'speaking' | 'error'
let speaking = false;
let waving = 0;            // remaining seconds of wave animation
const clock = new THREE.Clock();

window.TourAvatar = {
    setEmotion(name) {
        state = name;
        badge.className = name;
        badgeTx.textContent = ({
            idle: '就绪', listening: '聆听', thinking: '思考', speaking: '回答', error: '出错',
        })[name] || name;
        try { window.TourAvatarBridge?.onAvatarReady?.(); } catch (_) {}
    },
    setSpeaking(active) { speaking = !!active; },
    wave() { waving = 1.6; },
};

// ─── Render loop ──────────────────────────────────────────────────────
function tick() {
    const dt = clock.getDelta();
    const t  = clock.elapsedTime;

    // Idle breathing
    avatar.body.position.y = 0.95 + Math.sin(t * 1.6) * 0.012;
    avatar.head.position.y = 1.65 + Math.sin(t * 1.6) * 0.012;
    avatar.head.rotation.y = Math.sin(t * 0.6) * 0.08;
    avatar.head.rotation.x = Math.sin(t * 0.4) * 0.04;

    // Emotion-specific tweaks
    switch (state) {
        case 'listening':
            avatar.head.rotation.x = -0.18 + Math.sin(t * 1.2) * 0.03;
            break;
        case 'thinking':
            avatar.head.rotation.x = 0.10 + Math.sin(t * 0.8) * 0.04;
            avatar.head.rotation.y = 0.18 + Math.sin(t * 0.8) * 0.05;
            break;
        case 'error':
            avatar.head.rotation.z = Math.sin(t * 6) * 0.08;
            break;
    }

    // Mouth open/close while speaking (soft lerp, not jittery)
    const targetMouthY = speaking ? 0.55 + Math.sin(t * 14) * 0.30 : 0.25;
    avatar.mouth.scale.y += (targetMouthY - avatar.mouth.scale.y) * 0.25;

    // Wave one-shot
    if (waving > 0) {
        waving -= dt;
        const phase = (1.6 - waving) * 6;
        avatar.rightArm.rotation.z = -0.18 - 1.4 + Math.sin(phase) * 0.6;
        avatar.rightArm.position.y = 1.05;
    } else {
        avatar.rightArm.rotation.z += (-0.18 - avatar.rightArm.rotation.z) * 0.1;
        avatar.rightArm.position.y += (0.95 - avatar.rightArm.position.y) * 0.1;
    }

    renderer.render(scene, camera);
    requestAnimationFrame(tick);
}
tick();

// ─── Resize handling ──────────────────────────────────────────────────
function resize() {
    const w = stage.clientWidth, h = stage.clientHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);
}
new ResizeObserver(resize).observe(stage);
resize();

// Default emotion
TourAvatar.setEmotion('idle');
