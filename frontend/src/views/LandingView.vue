<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'

const scrolled = ref(false)
const searchQuery = ref('')
const section1Ref = ref<HTMLElement | null>(null)
const section2Ref = ref<HTMLElement | null>(null)
const section3Ref = ref<HTMLElement | null>(null)
const section4Ref = ref<HTMLElement | null>(null)

const contactForm = ref({
  name: '',
  email: '',
  company: '',
  message: ''
})

const handleContactSubmit = () => {
  // Handle form submission
  console.log('Contact form submitted:', contactForm.value)
  alert('Thank you for your message! We will get back to you soon.')
  contactForm.value = { name: '', email: '', company: '', message: '' }
}

const onScroll = () => {
  scrolled.value = window.scrollY > 60
}

const scrollToTop = () => {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

const scrollToSection = (id: string) => {
  const el = document.getElementById(id)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

const handleSearch = (e: Event) => {
  e.preventDefault()
}

const setupIntersectionObserver = () => {
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          const fadeEls = entry.target.querySelectorAll('.fade-in')
          fadeEls.forEach((el, i) => {
            setTimeout(() => el.classList.add('visible'), i * 120)
          })
        }
      })
    },
    { threshold: 0.12 }
  )
  ;[section1Ref.value, section2Ref.value, section3Ref.value, section4Ref.value].forEach((ref) => {
    if (ref) observer.observe(ref)
  })
  return observer
}

const results = [
  { code: 'GB 50016-2014', name: '建筑设计防火规范', category: 'Fire Safety' },
  { code: 'GB 50057-2013', name: '建筑防雷设计规范', category: 'Lightning Protection' },
  { code: 'GB 51251-2017', name: '建筑内部装修防火技术规范', category: 'Interior Finishing' },
  { code: 'JGJ 100-2015', name: '车库建筑设计规范', category: 'Parking' },
  { code: 'GB 50367-2013', name: '混凝土结构加固设计规范', category: 'Structural' },
]

let observer: IntersectionObserver | null = null

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  observer = setupIntersectionObserver()
})

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  observer?.disconnect()
})
</script>

<template>
  <div class="landing">
    <!-- Brand Grid Background -->
    <div class="landing__brand-grid" aria-hidden="true">
      <svg width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <pattern id="brandH" width="1" height="80" patternUnits="userSpaceOnUse">
            <line x1="0" y1="0" x2="100%" y2="0" stroke="#1A1815" stroke-width="0.5" vector-effect="non-scaling-stroke"/>
          </pattern>
          <pattern id="brandV" width="80" height="1" patternUnits="userSpaceOnUse">
            <line x1="0" y1="0" x2="0" y2="100%" stroke="#1A1815" stroke-width="0.5" vector-effect="non-scaling-stroke"/>
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#brandH)" opacity="0.035"/>
        <rect width="100%" height="100%" fill="url(#brandV)" opacity="0.035"/>
      </svg>
    </div>

    <!-- Navigation -->
    <nav class="landing-nav" :class="{ scrolled }">
      <a href="#" class="landing-nav__link" @click.prevent="scrollToTop">Search</a>
      <a href="#" class="landing-nav__link" @click.prevent="scrollToSection('features')">Features</a>
      <a href="#" class="landing-nav__link" @click.prevent="scrollToSection('data')">Data</a>
      <a href="#" class="landing-nav__link" @click.prevent="scrollToSection('contact')">Contact</a>
      <a href="/app/" class="landing-nav__link landing-nav__link--cta">Dashboard</a>
    </nav>

    <!-- Hero -->
    <section class="landing-hero" id="top">
      <div class="landing-hero__bg">
        <video
          class="landing-hero__video"
          src="/hero_video.mp4"
          autoPlay
          muted
          loop
          playsInline
          aria-hidden="true"
        />
        <svg class="landing-hero__grid" xmlns="http://www.w3.org/2000/svg" width="100%" height="100%">
          <defs>
            <pattern id="heroSmallGrid" width="40" height="40" patternUnits="userSpaceOnUse">
              <path d="M 40 0 L 0 0 0 40" fill="none" stroke="rgba(245,242,235,0.06)" stroke-width="0.5"/>
            </pattern>
            <pattern id="heroGrid" width="160" height="160" patternUnits="userSpaceOnUse">
              <rect width="160" height="160" fill="url(#heroSmallGrid)"/>
              <path d="M 160 0 L 0 0 0 160" fill="none" stroke="rgba(245,242,235,0.09)" stroke-width="0.75"/>
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#heroGrid)"/>
        </svg>
      </div>
      <div class="landing-hero__overlay"/>
      <div class="landing-hero__content">
        <h1 class="landing-hero__title">LS-ZGT</h1>
        <p class="landing-hero__subtitle">Building Standards. Intelligent Search.</p>
        <form class="landing-hero__search" @submit="handleSearch">
          <input
            v-model="searchQuery"
            type="text"
            class="landing-hero__search-input"
            placeholder="Search standards, codes, regulations..."
            aria-label="Search building standards"
          />
        </form>
      </div>
      <div class="landing-hero__scroll-hint">
        <span class="landing-hero__scroll-line"/>
      </div>
    </section>

    <!-- Search Demo Section -->
    <section class="landing-section" ref="section1Ref">
      <div class="landing-section__inner">
        <div class="search-demo">
          <div class="search-demo__text fade-in">
            <span class="section-number">01</span>
            <h2 class="search-demo__heading">Precision<br>at Scale</h2>
            <p class="search-demo__body">
              Natural language queries matched against structured regulation databases.
              Sub-second retrieval across twelve thousand standards.
            </p>
          </div>
          <div class="search-demo__right">
            <div class="search-demo__visual fade-in">
              <img
                class="search-demo__visual-img"
                src="/landing-search_001.jpg"
                alt="Search interface"
              />
            </div>
            <div class="search-demo__results fade-in">
              <a
                v-for="r in results"
                :key="r.code"
                href="#"
                class="search-demo__result"
              >
                <span class="result__code">{{ r.code }}</span>
                <span class="result__name">{{ r.name }}</span>
                <span class="result__cat">{{ r.category }}</span>
              </a>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Features Section -->
    <section class="landing-section landing-section--alt" id="features" ref="section2Ref">
      <div class="landing-section__inner">
        <div class="features-layout">
          <div class="features-left fade-in">
            <img
              class="features-visual-img"
              src="/landing-features_001.jpg"
              alt="Features dashboard"
            />
          </div>
          <div class="features-right fade-in">
            <span class="section-number">02</span>
            <h2 class="features-heading">Features</h2>
            <p class="features-body">
              Twelve thousand standards, indexed and cross-referenced. Browse by discipline,
              search by clause, compare across editions — all within a single structured interface.
            </p>
            <div class="features-list">
              <div class="feature-item">
                <span class="feature-item__num">01</span>
                <div>
                  <h3 class="feature-item__name">Classification</h3>
                  <p class="feature-item__desc">
                    Hierarchical taxonomy across fire safety, structural integrity, and environmental systems.
                  </p>
                </div>
              </div>
              <div class="feature-item">
                <span class="feature-item__num">02</span>
                <div>
                  <h3 class="feature-item__name">Smart Association</h3>
                  <p class="feature-item__desc">
                    Cross-reference engine connecting related standards, annotations, and amendment logs in real time.
                  </p>
                </div>
              </div>
              <div class="feature-item">
                <span class="feature-item__num">03</span>
                <div>
                  <h3 class="feature-item__name">Version Comparison</h3>
                  <p class="feature-item__desc">
                    Diff view between standard editions. Track changes, superseded clauses, and transition timelines.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Data Section -->
    <section class="landing-section" id="data" ref="section3Ref">
      <div class="landing-section__inner">
        <div class="data-layout">
          <div class="data-left fade-in">
            <span class="section-number">03</span>
            <h2 class="data-heading">Data</h2>
            <ul class="data-bullets">
              <li>Cross-referenced against official regulatory body publications</li>
              <li>Maintained by licensed structural and fire safety engineers</li>
            </ul>
            <div class="data-stats">
              <div class="data-stat">
                <span class="data-stat__num">12,400<span class="data-stat__plus">+</span></span>
                <span class="data-stat__label">Standards indexed</span>
              </div>
              <div class="data-stat">
                <span class="data-stat__num">98.7<span class="data-stat__pct">%</span></span>
                <span class="data-stat__label">Retrieval accuracy</span>
              </div>
              <div class="data-stat">
                <span class="data-stat__num">Real-time</span>
                <span class="data-stat__label">Update cycle</span>
              </div>
            </div>
          </div>
          <div class="data-right fade-in">
            <img
              class="data-visual-img"
              src="/landing-data_001.jpg"
              alt="Data visualization"
            />
          </div>
        </div>
      </div>
    </section>

    <!-- Contact Section -->
    <section class="landing-section landing-section--alt" id="contact" ref="section4Ref">
      <div class="landing-section__inner">
        <div class="contact-layout">
          <div class="contact-left fade-in">
            <span class="section-number">04</span>
            <h2 class="contact-heading">Contact</h2>
            <p class="contact-body">
              For inquiries regarding standards coverage, API access, or enterprise deployment,
              reach our team directly.
            </p>
            <div class="contact-details">
              <div class="contact-detail">
                <div class="contact-detail__icon">
                  <svg width="18" height="18" viewBox="0 0 18 18" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M3 4.5C3 3.67 3.67 3 4.5 3H13.5C14.33 3 15 3.67 15 4.5V13.5C15 14.33 14.33 15 13.5 15H4.5C3.67 15 3 14.33 3 13.5V4.5Z" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M3 5.25L9 9.375L15 5.25" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </div>
                <div class="contact-detail__content">
                  <span class="contact-detail__label">Email</span>
                  <span class="contact-detail__value">contact@lszgt.com</span>
                </div>
              </div>
              <div class="contact-detail">
                <div class="contact-detail__icon">
                  <svg width="18" height="18" viewBox="0 0 18 18" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M15.75 13.185V15.75C15.7501 15.9489 15.6742 16.1396 15.5389 16.2814C15.4036 16.4233 15.2195 16.5046 15.0255 16.5075C12.579 16.5428 10.186 15.8255 8.1375 14.4525C6.14515 13.1208 4.51963 11.3408 3.42225 9.27825C2.25 7.0125 1.5 4.5375 1.5 3C1.50289 2.80602 1.58419 2.62195 1.72601 2.48666C1.86784 2.35136 2.05853 2.27547 2.2575 2.2755H4.8225C5.17087 2.27306 5.49683 2.43041 5.71451 2.70612C5.93219 2.98183 6.01919 3.34675 5.95125 3.699C5.79777 4.54453 5.77737 5.40938 5.8905 6.2625C5.95183 6.73856 5.84945 7.22068 5.5995 7.63275L4.66575 9.05475C5.93651 11.4079 7.84268 13.3141 10.1955 14.5853L11.6175 13.6515C12.0296 13.4015 12.5117 13.2992 12.9878 13.3605C13.8409 13.4737 14.7057 13.4533 15.5513 13.2998C15.9035 13.2318 16.2684 13.3188 16.5441 13.5365C16.8199 13.7542 16.9772 14.0801 16.9748 14.4285V13.185C16.9748 12.9292 16.8741 12.6846 16.6944 12.5049C16.5147 12.3253 16.2701 12.2246 16.0143 12.2246C10.5975 12.2246 6.02625 7.65338 6.02625 2.23725C6.02625 1.98145 5.92555 1.7368 5.74588 1.55712C5.56621 1.37745 5.32155 1.27676 5.06575 1.27676C4.80995 1.27676 4.5653 1.37745 4.38562 1.55712C4.20595 1.7368 4.10525 1.98145 4.10525 2.23725C4.10525 2.49305 4.20595 2.7377 4.38562 2.91738C4.5653 3.09705 4.80995 3.19775 5.06575 3.19775C5.32155 3.19775 5.56621 3.09705 5.74588 2.91738C5.92555 2.7377 6.02625 2.49305 6.02625 2.23725V1.5" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </div>
                <div class="contact-detail__content">
                  <span class="contact-detail__label">Phone</span>
                  <span class="contact-detail__value">+86 21 8888 9999</span>
                </div>
              </div>
              <div class="contact-detail">
                <div class="contact-detail__icon">
                  <svg width="18" height="18" viewBox="0 0 18 18" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <path d="M9 9.75C10.2426 9.75 11.25 8.74264 11.25 7.5C11.25 6.25736 10.2426 5.25 9 5.25C7.75736 5.25 6.75 6.25736 6.75 7.5C6.75 8.74264 7.75736 9.75 9 9.75Z" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M9 1.5V3.75M9 14.25V16.5M16.5 7.5H14.25M3.75 7.5H1.5M14.8045 3.75L13.0725 5.4825M5.4825 13.0725L3.75 14.8045M16.5 10.5H14.25M3.75 10.5H1.5M14.8045 14.25L13.0725 12.5175M5.4825 4.9275L3.75 3.195" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                </div>
                <div class="contact-detail__content">
                  <span class="contact-detail__label">Address</span>
                  <span class="contact-detail__value">上海市浦东新区张江高科技园区</span>
                </div>
              </div>
            </div>
          </div>
          <div class="contact-right fade-in">
            <form class="contact-form" @submit.prevent="handleContactSubmit">
              <div class="contact-form__header">
                <h3 class="contact-form__title">Send us a message</h3>
                <p class="contact-form__subtitle">We typically respond within 24 hours</p>
              </div>
              <div class="contact-form__row">
                <div class="contact-form__field">
                  <input
                    id="contact-name"
                    v-model="contactForm.name"
                    type="text"
                    class="contact-form__input"
                    placeholder=" "
                    required
                  />
                  <label class="contact-form__label" for="contact-name">Name</label>
                </div>
                <div class="contact-form__field">
                  <input
                    id="contact-email"
                    v-model="contactForm.email"
                    type="email"
                    class="contact-form__input"
                    placeholder=" "
                    required
                  />
                  <label class="contact-form__label" for="contact-email">Email</label>
                </div>
              </div>
              <div class="contact-form__field">
                <input
                  id="contact-company"
                  v-model="contactForm.company"
                  type="text"
                  class="contact-form__input"
                  placeholder=" "
                />
                <label class="contact-form__label" for="contact-company">Company <span class="optional">(optional)</span></label>
              </div>
              <div class="contact-form__field contact-form__field--textarea">
                <textarea
                  id="contact-message"
                  v-model="contactForm.message"
                  class="contact-form__textarea"
                  placeholder=" "
                  rows="5"
                  required
                ></textarea>
                <label class="contact-form__label" for="contact-message">Message</label>
              </div>
              <button type="submit" class="contact-form__submit">
                <span>Send Message</span>
                <svg class="contact-form__submit-icon" width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M3 8H13M13 8L9 4M13 8L9 12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </button>
            </form>
          </div>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="landing-footer">
      <span class="landing-footer__brand">LS-ZGT</span>
      <nav class="landing-footer__nav">
        <a href="#" class="landing-footer__link" @click.prevent="scrollToTop">Search</a>
        <a href="#" class="landing-footer__link" @click.prevent="scrollToSection('features')">Features</a>
        <a href="#" class="landing-footer__link" @click.prevent="scrollToSection('data')">Data</a>
        <a href="#" class="landing-footer__link" @click.prevent="scrollToSection('contact')">Contact</a>
        <a href="/app/" class="landing-footer__link landing-footer__link--cta">Dashboard</a>
      </nav>
    </footer>
  </div>
</template>

<style src="../assets/styles/landing.css"></style>
