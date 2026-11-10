# Shoko WebUI visual theme

The native theme follows **Shoko Gray** in WebUI v2.5.13. It deliberately stays consistent in both Android system appearance modes.

| Role | Color |
| --- | --- |
| Page background | `#282E38` |
| Panels and header | `#2C333E` |
| Navigation and secondary buttons | `#353D4A` |
| Input background | `#1E2027` |
| Text/icons | `#CFD8E3` |
| Primary buttons and active controls | `#44A3FF` |
| Primary button text | `#010F1C` |
| Borders/dividers | `#21242B` |
| Selected rows | `#283654` |

Sora is bundled locally. Body text uses weight 400, semibold controls/headings 500, and bold 600. Panels, buttons and inputs have 8dp corners. Primary/secondary buttons use 2dp dark borders; panels use 1dp borders. Navigation uses blue active text/icons without a colored selection pill.

The mobile layout retains touch-friendly sizes, scrolling forms, and bottom navigation. Custom server CSS is not interpreted as native Android styling.

Authoritative references:

- [Theme colors](https://github.com/ShokoAnime/Shoko-WebUI/blob/v2.5.13/src/css/theme-shoko-gray.css)
- [Body styling](https://github.com/ShokoAnime/Shoko-WebUI/blob/v2.5.13/src/css/common.css)
- [Typography](https://github.com/ShokoAnime/Shoko-WebUI/blob/v2.5.13/src/css/tailwind.css)
- [Panel](https://github.com/ShokoAnime/Shoko-WebUI/blob/v2.5.13/src/components/Panels/ShokoPanel.tsx)
- [Buttons](https://github.com/ShokoAnime/Shoko-WebUI/blob/v2.5.13/src/components/Input/Button.tsx)
- [Sora font](https://github.com/google/fonts/tree/main/ofl/sora), with full OFL license in `licenses/Sora-OFL.txt`.
