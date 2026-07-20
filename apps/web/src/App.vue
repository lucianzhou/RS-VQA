<script setup>
import { computed, onBeforeUnmount, ref } from "vue";

const examples = [
  "图中有没有道路？",
  "图中有多少建筑物？",
  "建筑物覆盖面积是多少？",
  "建筑物比道路多吗？"
];

const imageFile = ref(null);
const imagePreview = ref("");
const question = ref("");
const result = ref(null);
const errorMessage = ref("");
const submitting = ref(false);
const fileInput = ref(null);

const canSubmit = computed(() => Boolean(imageFile.value && question.value.trim() && !submitting.value));
const hasImage = computed(() => Boolean(imageFile.value));
const isMockResult = computed(() => result.value?.predictionOrigin === "mock_demo");
const isAnswered = computed(() => result.value?.status === "answered");

function setQuestion(value) {
  question.value = value;
}

function chooseFile() {
  fileInput.value?.click();
}

function onFileChange(event) {
  const [file] = event.target.files ?? [];
  if (!file) {
    return;
  }
  updateImage(file);
}

function onDrop(event) {
  const [file] = event.dataTransfer.files ?? [];
  if (!file) {
    return;
  }
  updateImage(file);
}

function updateImage(file) {
  if (!file.type.startsWith("image/")) {
    errorMessage.value = "请选择 PNG、JPG、WEBP 等图片文件。";
    return;
  }
  if (imagePreview.value) {
    URL.revokeObjectURL(imagePreview.value);
  }
  imageFile.value = file;
  imagePreview.value = URL.createObjectURL(file);
  result.value = null;
  errorMessage.value = "";
}

function resetImage() {
  if (imagePreview.value) {
    URL.revokeObjectURL(imagePreview.value);
  }
  imagePreview.value = "";
  imageFile.value = null;
  result.value = null;
  errorMessage.value = "";
  if (fileInput.value) {
    fileInput.value.value = "";
  }
}

async function submitQuestion() {
  if (!canSubmit.value) {
    return;
  }

  submitting.value = true;
  result.value = null;
  errorMessage.value = "";

  try {
    const form = new FormData();
    form.append("image", imageFile.value);
    form.append("question", question.value.trim());
    const response = await fetch("/api/v1/vqa/answers", {
      method: "POST",
      body: form
    });
    const body = await response.json();
    if (!response.ok) {
      throw new Error(body.message || "本次请求无法处理，请检查图像和问题后重试。");
    }
    result.value = body;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "请求失败，请稍后再试。";
  } finally {
    submitting.value = false;
  }
}

onBeforeUnmount(() => {
  if (imagePreview.value) {
    URL.revokeObjectURL(imagePreview.value);
  }
});
</script>

<template>
  <main class="app-shell">
    <aside class="scene-rail" aria-label="当前影像上下文">
      <div class="rail-topline">
        <a class="brand" href="/" aria-label="RS-VQA 首页">
          <span class="brand-mark">RS</span>
          <span>RS–VQA</span>
        </a>
        <span class="rail-release">v0.1.2</span>
      </div>

      <div class="rail-intro">
        <p class="rail-kicker">IMAGE CONTEXT</p>
        <h1>把一张遥感图像带进对话。</h1>
        <p>先放入影像，再用自然语言提出一个受支持的问题。</p>
      </div>

      <section class="image-capsule" aria-labelledby="image-capsule-title">
        <div class="capsule-heading">
          <div>
            <p class="section-index">01 / INPUT</p>
            <h2 id="image-capsule-title">影像</h2>
          </div>
          <button v-if="imageFile" class="remove-button" type="button" @click="resetImage">移除</button>
        </div>

        <button
          class="image-stage"
          :class="{ 'has-image': imagePreview }"
          type="button"
          :aria-label="hasImage ? `更换影像：${imageFile.name}` : '选择或拖入遥感图像'"
          @click="chooseFile"
          @dragover.prevent
          @drop.prevent="onDrop"
        >
          <img
            v-if="imagePreview"
            :src="imagePreview"
            alt="当前待分析的遥感图像"
            width="1024"
            height="1024"
            decoding="async"
          />
          <template v-else>
            <span class="upload-glyph" aria-hidden="true">↥</span>
            <strong>选择一张影像</strong>
            <small>可点击选择，也可直接拖入</small>
          </template>
          <span class="image-stage-label">{{ hasImage ? "IMAGE / READY" : "IMAGE / INPUT" }}</span>
        </button>

        <input
          ref="fileInput"
          class="visually-hidden"
          type="file"
          accept="image/*"
          aria-label="选择遥感图像文件"
          @change="onFileChange"
        />
        <div class="image-footnote">
          <span class="image-state" :class="{ ready: hasImage }">
            <i></i>{{ hasImage ? "影像已就绪" : "等待影像" }}
          </span>
          <span v-if="imageFile" class="file-name">{{ imageFile.name }}</span>
          <span v-else>PNG · JPG · WEBP · 最大 10 MiB</span>
        </div>
      </section>

      <section class="scope-card" aria-labelledby="scope-title">
        <p class="section-index">MODEL SCOPE</p>
        <h2 id="scope-title">只回答已验证范围的问题</h2>
        <p>当前模型是 RSVQA-HR 闭集分类器；不把开放描述、风险判断或任意识别包装成模型答案。</p>
        <div class="scope-tags" aria-label="支持的问题类别">
          <span>存在</span><span>数量</span><span>面积</span><span>比较</span>
        </div>
      </section>
    </aside>

    <section class="chat-workbench" aria-label="遥感图像问答对话区">
      <header class="workbench-topbar">
        <div class="conversation-title">
          <span class="live-dot" aria-hidden="true"></span>
          <div>
            <p>新对话</p>
            <span>{{ hasImage ? "影像已加载，可开始提问" : "等待一张输入影像" }}</span>
          </div>
        </div>
        <span class="workbench-badge">LOCAL MVP · CLOSED-SET</span>
      </header>

      <div class="conversation-stream">
        <section v-if="!hasImage" class="empty-state" aria-labelledby="empty-state-title">
          <p class="conversation-overline">REMOTE SENSING / VISUAL QA</p>
          <h2 id="empty-state-title">从一张图像开始。</h2>
          <p>上传后，你可以直接用中文或英文提问。系统只会将明确属于研究模型已验证范围的问题送入推理。</p>
          <button class="empty-upload-button" type="button" @click="chooseFile">
            <span aria-hidden="true">＋</span> 选择本地影像
          </button>
        </section>

        <template v-else>
          <article class="context-message">
            <div class="message-avatar image-avatar" aria-hidden="true">图</div>
            <div class="context-message-body">
              <p class="message-label">当前输入</p>
              <strong>{{ imageFile.name }}</strong>
              <span>图像已加载。请在底部输入框提出一个问题。</span>
            </div>
          </article>

          <article v-if="result" class="analysis-message" aria-live="polite">
            <div class="message-avatar assistant-avatar" aria-hidden="true">RS</div>
            <div class="analysis-card">
              <div class="analysis-card-topline">
                <div>
                  <p class="message-label">分析结果</p>
                  <h2>{{ isAnswered ? (isMockResult ? "模拟回答" : "模型回答") : "当前无法回答" }}</h2>
                </div>
                <span class="status-pill" :class="result.status">{{ result.status }}</span>
              </div>

              <div v-if="isMockResult" class="mock-note">
                <span class="mock-note-mark">!</span>
                <p><strong>Mock 演示模式</strong>：以下答案仅用于验证界面与服务闭环，不是本研究 ViLT predicted-soft 模型输出。</p>
              </div>

              <p v-if="isAnswered" class="answer-signal">{{ result.answer }}</p>
              <p class="analysis-notice">{{ result.capabilityNotice }}</p>

              <details v-if="result.canonicalQuestion" class="analysis-details">
                <summary>查看本次识别到的问题协议</summary>
                <dl>
                  <div><dt>标准问题</dt><dd>{{ result.canonicalQuestion }}</dd></div>
                  <div><dt>问题类型</dt><dd>{{ result.questionType }}</dd></div>
                  <div><dt>输出来源</dt><dd>{{ result.predictionOrigin }}</dd></div>
                </dl>
              </details>
            </div>
          </article>

          <section v-else class="ready-state" aria-label="提问引导">
            <span class="ready-state-mark" aria-hidden="true">↗</span>
            <div>
              <strong>影像已准备好</strong>
              <p>例如可以问：图中有没有道路？</p>
            </div>
          </section>
        </template>
      </div>

      <div class="composer-area">
        <section v-if="errorMessage" class="inline-error" role="alert">
          <span aria-hidden="true">!</span>{{ errorMessage }}
        </section>

        <form class="question-composer" @submit.prevent="submitQuestion">
          <button class="attach-button" type="button" aria-label="选择或更换影像" @click="chooseFile">
            <span aria-hidden="true">＋</span>
          </button>
          <label class="visually-hidden" for="question">提出问题</label>
          <textarea
            id="question"
            v-model="question"
            name="question"
            maxlength="300"
            rows="1"
            autocomplete="off"
            aria-describedby="composer-help"
            placeholder="问问这张图像…"
            @keydown.meta.enter.prevent="submitQuestion"
            @keydown.ctrl.enter.prevent="submitQuestion"
          />
          <button class="send-button" type="submit" :disabled="!canSubmit">
            <span v-if="submitting" class="spinner" aria-hidden="true"></span>
            <span v-else aria-hidden="true">↑</span>
            <span class="send-label">{{ submitting ? "分析中" : "发送" }}</span>
          </button>
        </form>

        <div class="composer-meta">
          <div class="example-list" aria-label="可用问题示例">
            <button v-for="example in examples" :key="example" type="button" @click="setQuestion(example)">
              {{ example }}
            </button>
          </div>
          <p id="composer-help">模型仅在可识别的受支持范围内作答</p>
        </div>
      </div>
    </section>
  </main>
</template>
