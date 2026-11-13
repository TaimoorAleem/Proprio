# Paper plan

> The blog series is written during the build; a research paper is written once, after Proprio is finished, from the evidence in this repo (D-041). This file tracks which evidence would back which claim, so the paper can cite exact runs.

## Candidate contributions (strongest first)
1. **Research sensor → consumer smartwatch (question 2).** Converting through device profiles (CSF-1), a weighted-Kabsch rotation fit on lifts only, and pass/fail gates written before the held-out data was seen. Evidence: `docs/specs/phase3-domain-shift.md`, `docs/reports/phase1-*`, `docs/reports/phase3-*`, `watch-fit-v1` / `watch-val-v1` (`data/watch_datasets.yaml`), MLflow experiment `proprio-domain-shift`.
2. **How evaluation choices inflated the original result (question 1).** 0.9994 with leakage vs an honest LOPO baseline (window acc 0.963 ± 0.047). Likely a section of paper 1, not a paper on its own. Evidence: `docs/specs/phase0-leakage-fix.md`, `docs/reports/phase0-baseline*.md`, `current-baseline.md`, MLflow.
3. **Verifying prescribed home exercise (question 3, Phase 6).** Highest impact, hardest: needs clinicians, clients and ethics approval.

## Venue
To decide later: a workshop or conference on wearables / ubiquitous computing / health informatics, or an arXiv preprint first. Check the venue's policy on prior blog posts before submitting.

## Ethics status per dataset
| Dataset | Who | Status for a paper |
|---|---|---|
| MetaMotion (Ebbelaar) | 5 participants, third-party | Public dataset; cite [report]. Check its licence/terms before publishing derived results. |
| `watch-calib-v1`, `watch-fit-v1`, `watch-val-v1` (U1/U2) | the owner only | Own data; usable. |
| `watch-u3-v1` (U3) | friends | **Do not collect for research use without ethics approval.** Product consent forms are not research consent. |
| Phase 6+ clinician/client data | patients | Needs research ethics board (REB) approval and informed consent **before** collection. |

## Before collecting data from anyone else
- [ ] Find an academic collaborator (e.g. a Sheridan professor, or a kinesiology/physiotherapy researcher) to sponsor an REB application.
- [ ] Courtesy contact with Dave Ebbelaar (the work builds on his study and dataset).
- [ ] Pre-register gates and protocols where possible (the Phase 3 gates are already written in advance).

## Keep as we go
- Every claim in a blog part links to a report, decision or MLflow run that the paper can reuse.
- Record dataset ids, model versions and git commits in each report.
