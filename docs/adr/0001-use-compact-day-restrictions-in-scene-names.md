# Use compact day restrictions in scene names

Scene Schedules identify Day Restrictions with the compact `d:` prefix because Hue scene names have a restrictive length limit that is reached by realistic schedules. Inside the flag list, `,` separates top-level flags, `-` denotes an inclusive day range, and `;` separates distinct days or ranges—for example, `[i,d:Mo-Th;Su]`. Scene Schedule flags are parsed strictly: an unknown flag or invalid value makes the scene ineligible for scheduling. This representation balances compact names with an unambiguous grammar; input-file syntax is unaffected.
