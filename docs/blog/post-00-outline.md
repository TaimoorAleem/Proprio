# Blog 0 — outline and references (not the post)

> Status: outline agreed 2026-11-11; the post itself is not written yet. Series plan: [`../BLOG_PLAN.md`](../BLOG_PLAN.md).

**Working title:** From a YouTube tutorial to a real product: introducing Proprio

## Ground rules for this post (owner decisions)
- **Written as of the project's start.** It uses no results from blog 1 onwards: no leak findings, no 96.3%, no watch results. The series is published as development progresses.
- **The 99.9% claim:** present it as "the number I got by following the tutorial" and "the first thing I want to check". Don't hint at the outcome.
- **Section 3 stays detailed.**
- **The roadmap section** includes both what I'm building now (the phases) and the future ideas A–D from [`../ROADMAP_IDEAS.md`](../ROADMAP_IDEAS.md), labelled as ideas.

## Sections
1. **Where this started.** Dave Ebbelaar's YouTube series: what it builds, what it covers, and the 99.9% I got by following it.
2. **The original work behind the tutorial.** Ebbelaar's VU Amsterdam mini master project and dataset; ML4QS (Hoogendoorn & Funk); the related research his report cites.
3. **What a wrist sensor actually measures.** In detail:
   - accelerometer vs gyroscope;
   - gravity in the accelerometer;
   - units (g, m/s², deg/s, rad/s);
   - device axes;
   - sampling rate.
4. **How the tutorial pipeline works.** Raw CSVs → 200 ms resampling → outliers → low-pass filter, PCA, magnitudes, rolling and frequency features, clusters → five classifiers → a peak-based rep counter. One diagram.
5. **Why a notebook isn't a product.** A different sensor, new users, no app or backend, and an accuracy number nobody has re-checked.
6. **Why it's worth building: the research.** A crowded consumer space with common complaints, against an unsolved problem: verifying unsupervised prescribed exercise.
7. **Meet Proprio.** The vision: Galaxy Watch recorder, Android app, backend services, an LLM coach.
8. **The roadmap: what I'm building now.** The phases in plain words.
9. **The roadmap: where it could go.** Ideas A–D, explicitly not commitments.
10. **Ground rules for this series.** Real numbers including failures; every technology has a genuine job; posts follow development.
11. **What's next.** Blog 1: does 99.9% hold up?
12. **References.**

## References

**Status key:** **V** = checked against the source on 2026-11-11 · **P** = found, details only partly confirmed · **?** = not found or not yet checked (don't cite until confirmed).

### Origin of the project
| Ref | Status | Notes |
|---|---|---|
| Ebbelaar, D. "Full Machine Learning Project — Coding a Fitness Tracker with Python" (YouTube, 8 videos) — [playlist](https://youtube.com/playlist?list=PL-Y17yukoyy0sT2hoSQxn1TdV0J7-MX4K) | V | Read via transcripts and descriptions; details in `BLOG_PLAN.md` § Origins |
| Ebbelaar, D. *Exploring the Possibilities of Context-Aware Applications for Strength Training*, Mini Master Project, VU Amsterdam | V | Linked from the tutorial's part 2 material |
| Hoogendoorn, M. & Funk, B. (2018). *Machine Learning for the Quantified Self.* Springer — [link](https://link.springer.com/book/10.1007/978-3-319-66308-1); [ML4QS code](https://github.com/mhoogen/ML4QS/) | V | |
| MbientLab MetaMotion — [mbientlab.com/metamotions](https://mbientlab.com/metamotions/) | V | |

### The adherence problem
| Ref | Status | What it supports | Notes |
|---|---|---|---|
| Nicolson, P. et al. (2018). Self-reported home exercise adherence: a validity and reliability study using concealed accelerometers. *JOSPT* 48(12):943–950. doi:10.2519/jospt.2018.8275 | V | Diaries overstate adherence | 54 adults ≥45 with chronic knee pain; accelerometer hidden in the ankle cuff weight. Median 220 exercises (diary) vs 176 (accelerometer), P<.001, r = 0.52. The sensor was **ankle-worn, not wrist**; say so. |
| Picha, K. et al. (2021). Physical therapists' assessment of patient self-efficacy for home exercise programs. *IJSPT* 16(1):184–194. doi:10.26603/001c.18957 — [PMC7872465](https://pmc.ncbi.nlm.nih.gov/articles/PMC7872465/) | V | 50–70% non-adherence | Exact wording: "patient non-adherence with rehabilitation programs is 50%-70%". This is background the paper cites (its refs 2–4), not its own finding. Cite it as "cited in Picha et al." or cite the primary sources. |
| McArthur, C. et al. (2023). Older adults, clinicians, and researchers' preferences for measuring adherence to resistance and balance exercises. *BMC Geriatrics* 23:530. doi:10.1186/s12877-023-04237-x | V | Clinicians lack measurement tools | 88 older adults, 149 clinicians, 41 researchers; clinicians most often said they lacked measurement tools |
| ClinicalTrials.gov [NCT05032638](https://clinicaltrials.gov/study/NCT05032638), "Adherence in Upper Extremity Home-based Rehabilitation" | V | Wrist accelerometry used as an adherence measure | Wrist ActiGraph GT9X data is a **secondary** adherence outcome; the primary outcomes are frequency, intensity and duration |

### SPARS (Toronto smartwatch physio work)
| Ref | Status | Notes |
|---|---|---|
| Burns, D. M. et al. (2018). Shoulder physiotherapy exercise recognition: machine learning the inertial signals from a smartwatch. [arXiv:1802.01489](https://arxiv.org/abs/1802.01489) | V | University of Toronto, Waterloo and Sunnybrook; 7 rotator-cuff exercises; KNN, RF, SVC and a CRNN. v1 reports 10 subjects; the published version reports 20 |
| Burns, D. M. et al. (2020). Adherence tracking with smart watches for shoulder physiotherapy in rotator cuff pathology: protocol for a longitudinal cohort study. *JMIR Res Protoc* 9(7):e17841. doi:10.2196/17841 | V | Holland Centre affiliation; 120-patient cohort; clinic-labelled data used to classify home sessions |
| Related: 2021 cohort, *JMIR Rehabil Assist Technol* ([e21374](https://rehab.jmir.org/2021/1/e21374)), 42 patients; 2023 *Bone & Joint Research*, at-home detection AUROC 0.919; trial [NCT05139173](https://clinicaltrials.gov/study/NCT05139173) (SPARS-REHAB) | P | Useful supporting detail |
| "2025 case report vs diaries" | ? | **Not found.** Need its title, journal or PubMed ID before citing |

### What a wrist can measure
| Ref | Status | Notes |
|---|---|---|
| Achermann, B. et al. (2023). Velocity-based strength training: the validity and personal monitoring of barbell velocity with the Apple Watch. *Sports* 11(7):125 — [MDPI](https://www.mdpi.com/2075-4663/11/7/125) | V | Back squat vs Vicon. **Wrist-worn** Apple Watch had mean-velocity validity similar to bar-mounted devices; bar-mounted was best for peak velocity. Cite the paper, not the Enode summary |
| "Sensors 2025: wrist rep duration reliability" | P | Probable match: [Sensors 25(21):6686](https://www.mdpi.com/1424-8220/25/21/6686), a pilot with 25 adults and a wrist accelerometer. It covers **functional upper-extremity tasks, not weight training**; confirm before citing |
| Weakley, J. et al. (2021). The validity and reliability of commercially available resistance training monitoring devices: a systematic review. *Sports Med* 51:443–502. doi:10.1007/s40279-020-01382-w | V | Most studies lacked a gold-standard criterion |
| Thomas, J. & Wahlström, J. (2025). Estimation of resistance training RPE using inertial sensors and electromyography. [arXiv:2510.03197](https://arxiv.org/abs/2510.03197) | V | Preprint. RF: 41.4% exact, 85.9% within ±1 RPE; eccentric rep time was the strongest predictor; EMG helped little |
| Ziegler et al. (2023), *Int J Exerc Sci* conference abstract — four Garmin wrist devices | V | Conference abstract (not a full paper): 20 adults, light dumbbell circuits; no device met the validity criteria for rep counting |

### Competitors and complaints
| Ref | Status | Notes |
|---|---|---|
| Samsung Community threads on Galaxy Watch rep counting (e.g. [Watch 4 Classic](https://eu.community.samsung.com/t5/wearables/galaxy-watch-4-classic-rep-tracking-is-not-a-very-accurate/td-p/4955247)) | P | Anecdotal; mixed reports |
| TechRadar, "Why Garmin's strength training mode needs to be improved – or scrapped" — [link](https://techradar.com/features/why-garmins-strength-training-mode-needs-to-be-improved-or-scrapped) | P | Opinion feature |
| the5krunner, WHOOP Strength Trainer update (2026-02-28) — [link](https://the5krunner.com/2026/02/28/new-whoop-strength-trainer-update/) | V | "Passive MSK" automatic load estimation; not publicly validated per the article |
| the5krunner, WHOOP strength review (2023) | ? | Not located yet |
| Train Fitness, now **Motra**, App Store — [listing](https://apps.apple.com/us/app/motra-formerly-train-fitness/id1548577496) | P | The app has been **renamed**; its exercise counts differ between listings |

### Pricing and willingness to pay (mainly for blog 9 / the roadmap)
| Ref | Status | Notes |
|---|---|---|
| Enode sensor, $329 at SimpliFaster — [link](https://store.simplifaster.com/product/enode-sensor) | P | Retailer page, undated |
| TrueCoach pricing | P | Third-party sources disagree: about $30, $70 and $165/month (5, 20 and 50 clients) vs older lower tiers. Check the official page |
| Vmaxpro pricing; VBT buyer's guide; TrueCoach review (Trainera) | ? | Not located yet |
| CMS 2026 therapy code list — [MM14250](https://www.cms.gov/files/document/mm14250-therapy-code-list-2026-annual-update.pdf); RTM explainer — [Tenovi](https://www.tenovi.com/rtm-cpt-codes-2026/) | P | US remote therapeutic monitoring codes (98975, 98977, 98985). A CY 2027 rule revises their values; check before citing figures |

### Regulation
| Ref | Status | Notes |
|---|---|---|
| Health Canada, *Software as a Medical Device (SaMD): Definition and Classification* (2019) — [guidance](https://www.canada.ca/en/health-canada/services/drugs-health-products/medical-devices/application-information/guidance-documents/software-medical-device-guidance-document.html) | P | Official page found; PDF not read. General-wellness software is excluded |
| Legal500 summary of the Health Canada SaMD guidance | ? | Not located; cite the official guidance instead |
| FDA, *General Wellness: Policy for Low Risk Devices* (updated 2026-01-06) | P | Seen only through law-firm summaries (e.g. [Foley](https://www.foley.com/insights/publications/2026/01/digital-health-policy-fda-relaxes-restrictions-over-wearables-and-ai-decision-ma/)); cite FDA's own document |

### Before the post is written
- **Confirm or drop every `?` item.** For the SPARS 2025 case report, the owner to supply the title.
- **Cite primary sources over summaries:** Picha's cited sources, FDA's document over law-firm alerts, Achermann's paper over Enode's summary.
- **Section 6 is set before any development,** so it may use research and competitor facts, but nothing Proprio later measured.
