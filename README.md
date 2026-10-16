# TropiFix

Petit mod client Fabric (Minecraft 1.21.1) qui corrige des bugs de Cobblemon
rencontres en jeu sur Tropimon. Uniquement des mixins defensifs : aucun ajout
de fonctionnalite, aucun changement de comportement une fois le bug evite.

## Correctifs inclus

| Correctif | Probleme corrige |
|---|---|
| `PcGuiDragCrashMixin` | Crash `UninitializedPropertyAccessException: lateinit property storageWidget` quand on clique-glisse dans l'ecran du PC avant que les boites soient chargees (Cobblemon 1.7.x). |
| `WorldChunkClearCrashMixin` | Deconnexion "Erreur de protocole reseau" (`NullPointerException` dans `WorldChunk.clear`) quand Farsight recharge un chunk dont le cache est corrompu. |

## Minuteur d'evenements

A 0h00, 10h30, 15h30, 18h30 et 21h30 (heure de l'ordinateur), un minuteur de
5 minutes apparait a l'ecran. Il bipe quand il reste 1 minute et 30 secondes,
puis disparait sans bruit a 0. Connecte en cours de creneau, il demarre avec le temps restant.

- Deplacer : ouvrir le tchat (T) puis cliquer-glisser le minuteur.
- Fermer le minuteur en cours : clic droit dessus, tchat ouvert.
- Touches (Options > Controles, categorie TropiFix, non assignees par defaut) :
  "Verrouiller le minuteur" et "Tester le minuteur (1 min 10)".
- Position et verrouillage gardes dans `config/tropifix_timer.properties`.

## Build

Compile via GitHub Actions a chaque push sur `main`. Le jar se recupere dans
les artifacts du workflow.
