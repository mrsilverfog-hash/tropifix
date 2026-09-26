# TropiFix

Petit mod client Fabric (Minecraft 1.21.1) qui corrige des bugs de Cobblemon
rencontres en jeu sur Tropimon. Uniquement des mixins defensifs : aucun ajout
de fonctionnalite, aucun changement de comportement une fois le bug evite.

## Correctifs inclus

| Correctif | Probleme corrige |
|---|---|
| `PcGuiDragCrashMixin` | Crash `UninitializedPropertyAccessException: lateinit property storageWidget` quand on clique-glisse dans l'ecran du PC avant que les boites soient chargees (Cobblemon 1.7.x). |
| `WorldChunkClearCrashMixin` | Deconnexion "Erreur de protocole reseau" (`NullPointerException` dans `WorldChunk.clear`) quand Farsight recharge un chunk dont le cache est corrompu. |

## Build

Compile via GitHub Actions a chaque push sur `main`. Le jar se recupere dans
les artifacts du workflow.
