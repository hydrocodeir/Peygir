# Peygir Web — workspace design

The web client is a Persian, right-to-left operational workspace for meetings, resolutions and follow-up. Its visual direction uses the verified `data-dense-dashboard` style from `ui-ux-pro-max`, adapted for reading and acting on individual records. The existing Persian font and purple identity take precedence over generic palette or Latin typography suggestions.

## Structure

- Desktop: a 224px right sidebar, company header, page title, four contextual metrics, then the working content. At 1600px the sidebar grows to 248px.
- Tablet and mobile: four labeled bottom navigation items. Metrics become one compact row on narrow screens. Controls wrap without hiding labels or introducing page overflow.
- Meetings: visible search across all dates; daily, weekly and monthly controls; calendar plus a detailed agenda. Reservations retain a yellow surface and text label.
- Resolutions: visible search and filters; two-column cards on larger screens and one column on phones. Each card exposes status, priority, responsible person, due date, description, related meeting, latest follow-up and available actions.
- Follow-up: separate groups for extension requests, completion approvals, overdue work and approaching deadlines. Chiefs can approve or reject extension requests directly from cards.
- Forms and record details use visible named sections. Common fields, schedules, notes and history do not depend on a collapsed disclosure.

## Tokens

| Role | Light | Dark |
| --- | --- | --- |
| Canvas | `#f6f7fb` | `#13151f` |
| Surface | `#ffffff` | `#1c1e2b` |
| Main text | `#25233b` | `#f0eef8` |
| Secondary text | `#67667b` | `#b9b7ca` |
| Primary | `#6246a5` | `#c6afff` |
| On primary | `#ffffff` | `#2c194c` |
| Outline | `#e3e3ed` | `#393b4e` |
| Reservation | `#ffe082` / `#4e3900` | same accessible pair |

Font: locally hosted Vazirmatn. Main page titles 23–27px; card titles 16–17px; native text inputs at least 16px. Standard corner radius 16px, smaller controls 8–12px. Spacing uses a consistent 4/8px rhythm with 16–24px content padding.

## Interaction and accessibility

- Use visible text with action icons. Keep an accessible name on icon-only controls and stable navigation labels.
- Show state with text and color together. Use native buttons, inputs, checkboxes, progress elements and dialogs.
- Keep controls at least 44px in touch interfaces. All keyboard focus indicators remain visible.
- Show save and validation feedback, focus the error summary, preserve input focus during background refresh, and retain the current search when switching quick filters.
- Respect reduced motion. Theme transitions settle before screenshot verification; dark and light surfaces are checked independently.
- Keep pagination for large task lists. Do not mount every record during initial rendering.

## Implementation

`vps/web/style.css` owns the CSS variables, responsive layout and shared components. `vps/web/app.js` renders the existing application with the same authentication, account scope, offline queue and synchronization APIs. No new frontend runtime dependency is required.
