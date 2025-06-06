package mage.game.combat;

import mage.abilities.Ability;
import mage.abilities.common.ControllerAssignCombatDamageToBlockersAbility;
import mage.abilities.common.ControllerDivideCombatDamageAbility;
import mage.abilities.common.DamageAsThoughNotBlockedAbility;
import mage.abilities.keyword.*;
import mage.constants.AsThoughEffectType;
import mage.constants.MultiAmountType;
import mage.constants.Outcome;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.events.BlockerDeclaredEvent;
import mage.game.events.DeclareBlockerEvent;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.util.Copyable;
import mage.util.MultiAmountMessage;
import mage.watchers.common.FirstStrikeWatcher;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Stream;

/**
 * @author BetaSteward_at_googlemail.com
 */
public class CombatGroup implements Serializable, Copyable<CombatGroup> {

    protected List<UUID> attackers = new ArrayList<>();
    protected List<UUID> formerAttackers = new ArrayList<>();
    protected List<UUID> blockers = new ArrayList<>();
    protected Map<UUID, UUID> players = new HashMap<>();
    protected boolean blocked;
    protected UUID defenderId; // planeswalker, player, or battle id, can be null after remove from combat (e.g. due damage)
    protected UUID defendingPlayerId;
    protected boolean defenderIsPermanent;

    /**
     * @param defenderId          player, planeswalker or battle that defending
     * @param defenderIsPermanent is the defender a permanent
     * @param defendingPlayerId   regular controller of the defending permanents
     */
    public CombatGroup(UUID defenderId, boolean defenderIsPermanent, UUID defendingPlayerId) {
        this.defenderId = defenderId;
        this.defenderIsPermanent = defenderIsPermanent;
        this.defendingPlayerId = defendingPlayerId;
    }

    protected CombatGroup(final CombatGroup group) {
        this.attackers.addAll(group.attackers);
        this.formerAttackers.addAll(group.formerAttackers);
        this.blockers.addAll(group.blockers);
        this.players.putAll(group.players);
        this.blocked = group.blocked;
        this.defenderId = group.defenderId;
        this.defendingPlayerId = group.defendingPlayerId;
        this.defenderIsPermanent = group.defenderIsPermanent;
    }

    public boolean hasFirstOrDoubleStrike(Game game) {
        return Stream.concat(attackers.stream(), blockers.stream())
                .map(game::getPermanent)
                .filter(Objects::nonNull)
                .anyMatch(CombatGroup::hasFirstOrDoubleStrike);
    }

    /**
     * @return can be null
     */
    public UUID getDefenderId() {
        return defenderId;
    }

    public UUID getDefendingPlayerId() {
        return defendingPlayerId;
    }

    public List<UUID> getAttackers() {
        return attackers;
    }

    public List<UUID> getFormerAttackers() {
        return formerAttackers;
    }

    public List<UUID> getBlockers() {
        return blockers;
    }

    private static boolean hasFirstOrDoubleStrike(Permanent perm) {
        return hasFirstStrike(perm) || hasDoubleStrike(perm);
    }

    private static boolean hasFirstStrike(Permanent perm) {
        return perm.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId());
    }

    private static boolean hasDoubleStrike(Permanent perm) {
        return perm.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId());
    }

    private static boolean hasTrample(Permanent perm) {
        return perm.getAbilities().containsKey(TrampleAbility.getInstance().getId());
    }

    private static boolean hasTrampleOverPlaneswalkers(Permanent perm) {
        return perm.getAbilities().containsKey(TrampleOverPlaneswalkersAbility.getInstance().getId());
    }

    private static boolean hasBanding(Permanent perm) {
        return perm.getAbilities().containsKey(BandingAbility.getInstance().getId());
    }

    private boolean appliesBandsWithOther(List<UUID> creatureIds, Game game) {
        for (UUID creatureId : creatureIds) {
            Permanent perm = game.getPermanent(creatureId);
            if (perm != null && perm.getBandedCards() != null) {
                for (Ability ab : perm.getAbilities()) {
                    if (ab.getClass().equals(BandsWithOtherAbility.class)) {
                        BandsWithOtherAbility ability = (BandsWithOtherAbility) ab;
                        if (ability.getSubtype() != null) {
                            if (perm.hasSubtype(ability.getSubtype(), game)) {
                                for (UUID bandedId : creatureIds) {
                                    if (!bandedId.equals(creatureId)) {
                                        Permanent banded = game.getPermanent(bandedId);
                                        if (banded != null && banded.hasSubtype(ability.getSubtype(), game)) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                        if (ability.getSupertype() != null) {
                            if (perm.getSuperType(game).contains(ability.getSupertype())) {
                                for (UUID bandedId : creatureIds) {
                                    if (!bandedId.equals(creatureId)) {
                                        Permanent banded = game.getPermanent(bandedId);
                                        if (banded != null && banded.getSuperType(game).contains(ability.getSupertype())) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                        if (ability.getName() != null) {
                            if (perm.getName().equals(ability.getName())) {
                                for (UUID bandedId : creatureIds) {
                                    if (!bandedId.equals(creatureId)) {
                                        Permanent banded = game.getPermanent(bandedId);
                                        if (banded != null && banded.getName().equals(ability.getName())) {
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    public void assignDamageToBlockers(boolean first, Game game) {
        if (!attackers.isEmpty() && (!first || hasFirstOrDoubleStrike(game))) {
            Permanent attacker = game.getPermanent(attackers.get(0));
            if (attacker != null && !assignsDefendingPlayerAndOrDefendingCreaturesDividedDamage(attacker, attacker.getControllerId(), first, game, true)) {
                if (blockers.isEmpty()) {
                    unblockedDamage(first, game);
                } else {
                    Player player = game.getPlayer(defenderAssignsCombatDamage(game) ? defendingPlayerId : attacker.getControllerId());
                    if ((attacker.getAbilities().containsKey(DamageAsThoughNotBlockedAbility.getInstance().getId()) &&
                            player.chooseUse(Outcome.Damage, "Have " + attacker.getLogName() + " assign damage as though it weren't blocked?", null, game)) ||
                            !game.getContinuousEffects().asThough(attacker.getId(), AsThoughEffectType.DAMAGE_NOT_BLOCKED,
                                    null, attacker.getControllerId(), game).isEmpty()) {
                        // for handling creatures like Thorn Elemental
                        blocked = false;
                        unblockedDamage(first, game);
                    }
                    blockerDamage(player, first, game);
                }
            }
        }
    }

    public void assignDamageToAttackers(boolean first, Game game) {
        if (!blockers.isEmpty() && (!first || hasFirstOrDoubleStrike(game))) {
            // this should only come up if Butcher Orgg is granted the ability to block multiple blockers
            for (UUID blockerId : blockers) {
                Permanent blocker = game.getPermanent(blockerId);
                if (assignsDefendingPlayerAndOrDefendingCreaturesDividedDamage(blocker, blocker.getControllerId(), first, game, false)) {
                    return;
                }
            }
            if (attackers.size() != 1) {
                attackerDamage(first, game);
            }
        }
    }

    public void applyDamage(Game game) {
        for (UUID uuid : attackers) {
            Permanent permanent = game.getPermanent(uuid);
            if (permanent != null) {
                permanent.applyDamage(game);
            }
        }
        for (UUID uuid : blockers) {
            Permanent permanent = game.getPermanent(uuid);
            if (permanent != null) {
                permanent.applyDamage(game);
            }
        }
        if (defenderIsPermanent) {
            Permanent permanent = game.getPermanent(defenderId);
            if (permanent != null) {
                permanent.applyDamage(game);
            }
        }
    }

    /**
     * Determines if permanent is to deal damage this step based on whether it has first/double strike
     * and whether it did during the first combat damage step of this phase.
     * Info is stored in FirstStrikeWatcher.
     *
     * @param perm  Permanent to check
     * @param first true for first strike damage step, false for normal damage step
     * @return true if permanent should deal damage this step
     */
    public static boolean dealsDamageThisStep(Permanent perm, boolean first, Game game) {
        if (perm == null) {
            return false;
        }
        if (first) {
            if (hasFirstOrDoubleStrike(perm)) {
                FirstStrikeWatcher.recordFirstStrikingCreature(perm.getId(), game);
                return true;
            }
            return false;
        } else { // 702.7c
            return hasDoubleStrike(perm) || !FirstStrikeWatcher.wasFirstStrikingCreature(perm.getId(), game);
        }
    }

    private void unblockedDamage(boolean first, Game game) {
        for (UUID attackerId : attackers) {
            Permanent attacker = game.getPermanent(attackerId);
            if (dealsDamageThisStep(attacker, first, game)) {
                //20091005 - 510.1c, 702.17c
                if (!blocked || hasTrample(attacker)) {
                    defenderDamage(attacker, getDamageValueFromPermanent(attacker, game), game, false);
                }
            }
        }
    }
    private void blockerDamage(Player player, boolean first, Game game) {
        Permanent attacker = game.getPermanent(attackers.get(0));
        if (attacker == null) {
            return;
        }
        int damage = getDamageValueFromPermanent(attacker, game);
        if (dealsDamageThisStep(attacker, first, game)) {
            // must be set before attacker damage marking because of effects like Test of Faith
            Map<UUID, Integer> blockerPower = new HashMap<>();
            for (UUID blockerId : blockers) {
                Permanent blocker = game.getPermanent(blockerId);
                if (dealsDamageThisStep(blocker, first, game)) {
                    if (checkSoleBlockerAfter(blocker, game)) { // blocking several creatures handled separately
                        blockerPower.put(blockerId, getDamageValueFromPermanent(blocker, game));
                    }
                }
            }
            Map<UUID, Integer> assigned = new HashMap<>();
            List<MultiAmountMessage> damageDivision = new ArrayList<>();
            List<UUID> blockersCopy = new ArrayList<>(blockers);
            if (blocked) {
                int remainingDamage = damage;
                for (UUID blockerId : blockers) {
                    Permanent blocker = game.getPermanent(blockerId);
                    if (blocker != null) {
                        int defaultDamage = Math.min(remainingDamage, blocker.getLethalDamage(attacker.getId(), game));
                        remainingDamage -= defaultDamage;
                        String message = String.format("%s, P/T: %d/%d",
                                blocker.getLogName(),
                                blocker.getPower().getValue(),
                                blocker.getToughness().getValue());
                        damageDivision.add(new MultiAmountMessage(message, 0, damage, defaultDamage));
                    }
                }
                List<Integer> amounts;
                if (hasTrample(attacker)){
                    if (remainingDamage > 0 || damageDivision.size() > 1) {
                        MultiAmountType dialogue = new MultiAmountType("Assign combat damage (with trample)",
                                String.format("Assign combat damage among creatures blocking %s, P/T: %d/%d (Unassigned damage tramples through)",
                                        attacker.getLogName(), attacker.getPower().getValue(), attacker.getToughness().getValue()));
                        amounts = player.getMultiAmountWithIndividualConstraints(Outcome.Damage, damageDivision, damage - remainingDamage, damage, dialogue, game);
                    } else {
                        amounts = new ArrayList<>();
                        if (damageDivision.size() == 1) { // Assign all damage to one blocker
                            amounts.add(damage);
                        }
                    }
                    int trampleDamage = damage - (amounts.stream().mapToInt(x -> x).sum());
                    if (trampleDamage > 0) {
                        defenderDamage(attacker, trampleDamage, game, false);
                    }
                } else {
                    if (remainingDamage > 0){
                        damageDivision.get(0).defaultValue += remainingDamage;
                    }
                    if (damageDivision.size() > 1) {
                        MultiAmountType dialogue = new MultiAmountType("Assign combat damage",
                                String.format("Assign combat damage among creatures blocking %s, P/T: %d/%d",
                                        attacker.getLogName(), attacker.getPower().getValue(), attacker.getToughness().getValue()));
                        amounts = player.getMultiAmountWithIndividualConstraints(Outcome.Damage, damageDivision, damage, damage, dialogue, game);
                    } else {
                        amounts = new LinkedList<>();
                        if (damageDivision.size() == 1) { // Assign all damage to one blocker
                            amounts.add(damage);
                        }
                    }
                }
                if (!damageDivision.isEmpty()){
                    for (int i=0; i<blockersCopy.size(); i++) {
                        assigned.put(blockersCopy.get(i), amounts.get(i));
                    }
                }
            }
            for (UUID blockerId : blockers) {
                Integer power = blockerPower.get(blockerId);
                if (power != null) {
                    // might be missing canDamage condition?
                    Permanent blocker = game.getPermanent(blockerId);
                    if (blocker != null && !assignsDefendingPlayerAndOrDefendingCreaturesDividedDamage(blocker, blocker.getControllerId(), first, game, false)) {
                        attacker.markDamage(power, blockerId, null, game, true, true);
                    }
                }
            }
            for (Map.Entry<UUID, Integer> entry : assigned.entrySet()) {
                Permanent blocker = game.getPermanent(entry.getKey());
                if (blocker != null) {
                    blocker.markDamage(entry.getValue(), attacker.getId(), null, game, true, true);
                }
            }
        } else {
            for (UUID blockerId : blockers) {
                Permanent blocker = game.getPermanent(blockerId);
                if (dealsDamageThisStep(blocker, first, game)) {
                    if (!assignsDefendingPlayerAndOrDefendingCreaturesDividedDamage(blocker, blocker.getControllerId(), first, game, false)) {
                        attacker.markDamage(getDamageValueFromPermanent(blocker, game), blocker.getId(), null, game, true, true);
                    }
                }
            }
        }
    }

    private void defendingPlayerAndOrDefendingCreaturesDividedDamage(Permanent attacker, Player player, boolean first, Game game, boolean isAttacking) {
        // for handling Butcher Orgg
        if (!((blocked && blockers.isEmpty() && isAttacking) || (attackers.isEmpty() && !isAttacking))) {
            if (attacker == null) {
                return;
            }
            int damage = getDamageValueFromPermanent(attacker, game);
            if (dealsDamageThisStep(attacker, first, game)) {
                // must be set before attacker damage marking because of effects like Test of Faith
                Map<UUID, Integer> blockerPower = new HashMap<>();
                for (UUID blockerId : blockers) {
                    Permanent blocker = game.getPermanent(blockerId);
                    if (dealsDamageThisStep(blocker, first, game)) {
                        if (checkSoleBlockerAfter(blocker, game)) { // blocking several creatures handled separately
                            blockerPower.put(blockerId, getDamageValueFromPermanent(blocker, game));
                        }
                    }
                }
                Map<UUID, Integer> assigned = new HashMap<>();
                for (Permanent defendingCreature : game.getBattlefield().getAllActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURE, defendingPlayerId, game)) {
                    if (defendingCreature != null) {
                        if (!(damage > 0)) {
                            break;
                        }
                        int damageAssigned = 0;
                        damageAssigned = player.getAmount(0, damage, "Assign damage to " + defendingCreature.getName(), game);
                        assigned.put(defendingCreature.getId(), damageAssigned);
                        damage -= damageAssigned;
                    }
                }
                if (damage > 0) {
                    Player defendingPlayer = game.getPlayer(defendingPlayerId);
                    if (defendingPlayer != null) {
                        defendingPlayer.damage(damage, attacker.getId(), null, game, true, true);
                    }
                }
                if (isAttacking) {
                    for (UUID blockerId : blockers) {
                        Integer power = blockerPower.get(blockerId);
                        if (power != null) {
                            // might be missing canDamage condition?
                            Permanent blocker = game.getPermanent(blockerId);
                            if (!assignsDefendingPlayerAndOrDefendingCreaturesDividedDamage(blocker, blocker.getControllerId(), first, game, false)) {
                                attacker.markDamage(power, blockerId, null, game, true, true);
                            }
                        }
                    }
                }
                for (Map.Entry<UUID, Integer> entry : assigned.entrySet()) {
                    Permanent defendingCreature = game.getPermanent(entry.getKey());
                    defendingCreature.markDamage(entry.getValue(), attacker.getId(), null, game, true, true);
                }
            } else {
                if (isAttacking) {
                    for (UUID blockerId : blockers) {
                        Permanent blocker = game.getPermanent(blockerId);
                        if (dealsDamageThisStep(blocker, first, game)) {
                            if (!assignsDefendingPlayerAndOrDefendingCreaturesDividedDamage(blocker, blocker.getControllerId(), first, game, false)) {
                                attacker.markDamage(getDamageValueFromPermanent(blocker, game), blocker.getId(), null, game, true, true);
                            }
                        }
                    }
                }
            }
        }
    }

    public boolean checkSoleBlockerAfter(Permanent blocker, Game game) {
        // this solves some corner cases (involving banding) when finding out whether a blocker is blocking alone or not
        if (blocker.getBlocking() == 1) {
            if (game.getCombat().blockingGroups.get(blocker.getId()) == null) {
                return true;
            } else {
                for (CombatGroup group : game.getCombat().getBlockingGroups()) {
                    if (group.blockers.contains(blocker.getId())) {
                        if (group.attackers.size() == 1) {
                            return true; // if blocker is blocking a band, this won't be true
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Damages attacking creatures by a creature that blocked several ones
     * Damages only attackers as blocker was damage in
     * {@link #blockerDamage}.
     * <p>
     * Handles abilities like "{this} can block any number of creatures.".
     *
     * @param first
     * @param game
     */
    private void attackerDamage(boolean first, Game game) {
        Permanent blocker = game.getPermanent(blockers.get(0));
        if (blocker == null) {
            return;
        }
        //Handle Banding
        Player player = game.getPlayer(attackerAssignsCombatDamage(game) ? game.getCombat().getAttackingPlayerId() : blocker.getControllerId());
        int damage = getDamageValueFromPermanent(blocker, game);

        if (dealsDamageThisStep(blocker, first, game)) {
            Map<UUID, Integer> assigned = new HashMap<>();
            List<MultiAmountMessage> damageDivision = new ArrayList<>();
            List<UUID> attackersCopy = new ArrayList<>(attackers);
            int remainingDamage = damage;
            for (UUID attackerId : attackers) {
                Permanent attacker = game.getPermanent(attackerId);
                if (attacker != null) {
                    int defaultDamage = Math.min(remainingDamage, attacker.getLethalDamage(blocker.getId(), game));
                    remainingDamage -= defaultDamage;
                    String message = String.format("%s, P/T: %d/%d",
                            attacker.getLogName(),
                            attacker.getPower().getValue(),
                            attacker.getToughness().getValue());
                    damageDivision.add(new MultiAmountMessage(message, 0, damage, defaultDamage));
                }
            }
            List<Integer> amounts;
            if (remainingDamage > 0){
                damageDivision.get(0).defaultValue += remainingDamage;
            }
            if (damageDivision.size() > 1) {
                MultiAmountType dialogue = new MultiAmountType("Assign blocker combat damage",
                        String.format("Assign combat damage among creatures blocked by %s, P/T: %d/%d",
                                blocker.getLogName(), blocker.getPower().getValue(), blocker.getToughness().getValue()));
                amounts = player.getMultiAmountWithIndividualConstraints(Outcome.Damage, damageDivision, damage, damage, dialogue, game);
            } else {
                amounts = new LinkedList<>();
                amounts.add(damage);
            }
            if (!damageDivision.isEmpty()){
                for (int i=0; i<attackersCopy.size(); i++) {
                    assigned.put(attackersCopy.get(i), amounts.get(i));
                }
            }
            for (Map.Entry<UUID, Integer> entry : assigned.entrySet()) {
                Permanent attacker = game.getPermanent(entry.getKey());
                if (attacker != null) {
                    attacker.markDamage(entry.getValue(), blocker.getId(), null, game, true, true);
                }
            }
        }
    }

    /**
     * Do damage to attacked player or planeswalker
     *
     * @param attacker
     * @param amount
     * @param game
     * @param damageToDefenderController excess damage to defender's controller (example: trample over planeswalker)
     */
    private void defenderDamage(Permanent attacker, int amount, Game game, boolean damageToDefenderController) {
        UUID affectedDefenderId = damageToDefenderController ? game.getControllerId(this.defenderId) : this.defenderId;

        // on planeswalker
        Permanent permanent = game.getPermanent(affectedDefenderId);
        if (permanent == null) {// on player
            Player defender = game.getPlayer(affectedDefenderId);
            if (defender != null) {
                defender.damage(amount, attacker.getId(), null, game, true, true);
            }
            return;
        }
        // apply excess damage from "trample over planeswaslkers" ability (example: Thrasta, Tempest's Roar)
        if (permanent.isPlaneswalker(game) && hasTrampleOverPlaneswalkers(attacker)) {
            int lethalDamage = permanent.getLethalDamage(attacker.getId(), game);
            if (lethalDamage >= amount) {
                // normal damage
                permanent.markDamage(amount, attacker.getId(), null, game, true, true);
            } else {
                // damage with excess (additional damage to permanent's controller)
                permanent.markDamage(lethalDamage, attacker.getId(), null, game, true, true);
                amount -= lethalDamage;
                if (amount > 0) {
                    defenderDamage(attacker, amount, game, true);
                }
            }
        } else {
            // normal damage
            permanent.markDamage(amount, attacker.getId(), null, game, true, true);
        }
    }

    public boolean canBlock(Permanent blocker, Game game) {
        // player can't block if another player is attacked
        if (!defendingPlayerId.equals(blocker.getControllerId())) {
            return false;
        }
        for (UUID attackerId : attackers) {
            if (!blocker.canBlock(attackerId, game)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param blockerId
     * @param playerId  controller of the blocking creature
     * @param game
     */
    public void addBlocker(UUID blockerId, UUID playerId, Game game) {
        for (UUID attackerId : attackers) {
            if (game.replaceEvent(new DeclareBlockerEvent(attackerId, blockerId, playerId))) {
                return;
            }
        }
        addBlockerToGroup(blockerId, playerId, game);
    }

    /**
     * Adds a blocker to a combat group without creating a DECLARE_BLOCKER
     * event.
     *
     * @param blockerId
     * @param playerId  controller of the blocking creature
     * @param game
     */
    public void addBlockerToGroup(UUID blockerId, UUID playerId, Game game) {
        Permanent blocker = game.getPermanent(blockerId);
        if (blockerId != null && blocker != null) {
            blocker.setBlocking(blocker.getBlocking() + 1);
            blockers.add(blockerId);
            this.blocked = true;
            this.players.put(blockerId, playerId);
        }
    }

    private void logDamageAssignmentOrder(String prefix, List<UUID> assignedFor, List<UUID> assignedOrder, Game game) {
        StringBuilder sb = new StringBuilder(prefix);
        boolean first = true;
        for (UUID id : assignedFor) {
            Permanent perm = game.getPermanent(id);
            if (perm != null) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(perm.getLogName());
                first = false;
            }
        }
        sb.append(" are ordered: ");
        first = true;
        for (UUID id : assignedOrder) {
            Permanent perm = game.getPermanent(id);
            if (perm != null) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(perm.getLogName());
                first = false;
            }
        }
        game.informPlayers(sb.toString());
    }

    public boolean isDefenderIsPermanent() {
        return defenderIsPermanent;
    }

    public boolean removeAttackedPermanent(UUID permanentId) {
        if (defenderIsPermanent && defenderId.equals(permanentId)) {
            defenderId = null;
            return true;
        }
        return false;
    }

    public boolean remove(UUID creatureId) {
        boolean result = false;
        if (attackers.contains(creatureId)) {
            formerAttackers.add(creatureId);
            attackers.remove(creatureId);
            result = true;
        } else if (blockers.contains(creatureId)) {
            blockers.remove(creatureId);
            result = true;
        }
        return result;
    }

    public void acceptBlockers(Game game) {
        if (attackers.isEmpty()) {
            return;
        }
        for (UUID blockerId : blockers) {
            for (UUID attackerId : attackers) {
                game.fireEvent(new BlockerDeclaredEvent(attackerId, blockerId, players.get(blockerId)));
            }
        }

        if (!blockers.isEmpty()) {
            for (UUID attackerId : attackers) {
                game.fireEvent(GameEvent.getEvent(GameEvent.EventType.CREATURE_BLOCKED, attackerId, null));
            }
        }
    }

    public boolean checkBlockRestrictions(Game game, Player defender, int blockersCount) {
        boolean blockWasLegal = true;
        if (attackers.isEmpty()) {
            return blockWasLegal;
        }

        // collect possible blockers
        Map<UUID, Set<UUID>> possibleBlockers = new HashMap<>();
        for (UUID attackerId : attackers) {
            Permanent attacker = game.getPermanent(attackerId);
            Set<UUID> goodBlockers = new HashSet<>();
            for (Permanent blocker : game.getBattlefield().getActivePermanents(StaticFilters.FILTER_PERMANENT_CREATURES_CONTROLLED, defender.getId(), game)) {
                if (blocker.canBlock(attackerId, game)) {
                    goodBlockers.add(blocker.getId());
                }
            }
            possibleBlockers.put(attacker.getId(), goodBlockers);
        }

        // effects: can't block alone
        // too much blockers
        if (blockersCount == 1) {
            List<UUID> toBeRemoved = new ArrayList<>();
            for (UUID blockerId : getBlockers()) {
                Permanent blocker = game.getPermanent(blockerId);
                if (blocker != null && blocker.getAbilities().containsKey(CantBlockAloneAbility.getInstance().getId())) {
                    blockWasLegal = false;
                    if (!game.isSimulation()) {
                        game.informPlayers(blocker.getLogName() + " can't block alone. Removing it from combat.");
                    }
                    toBeRemoved.add(blockerId);
                }
            }

            for (UUID blockerId : toBeRemoved) {
                game.getCombat().removeBlocker(blockerId, game);
            }
            if (blockers.isEmpty()) {
                this.blocked = false;
            }
        }

        for (UUID uuid : attackers) {
            Permanent attacker = game.getPermanent(uuid);
            if (attacker != null && this.blocked) {
                // effects: can't be blocked except by xxx or more creatures
                // too few blockers
                if (attacker.getMinBlockedBy() > 1 && !blockers.isEmpty() && blockers.size() < attacker.getMinBlockedBy()) {
                    for (UUID blockerId : new ArrayList<>(blockers)) {
                        game.getCombat().removeBlocker(blockerId, game);
                    }
                    blockers.clear();
                    if (!game.isSimulation()) {
                        game.informPlayers(attacker.getLogName() + " can't be blocked except by " + attacker.getMinBlockedBy() + " or more creatures. Blockers discarded.");
                    }

                    // if there aren't any possible blocker configuration then it's legal due mtg rules
                    // warning, it's affect AI related logic like other block auto-fixes does, see https://github.com/magefree/mage/pull/13182
                    if (attacker.getMinBlockedBy() <= possibleBlockers.getOrDefault(attacker.getId(), Collections.emptySet()).size()) {
                        blockWasLegal = false;
                    }
                }

                // effects: can't be blocked by more than xxx creature
                // too much blockers
                if (attacker.getMaxBlockedBy() > 0 && attacker.getMaxBlockedBy() < blockers.size()) {
                    for (UUID blockerId : new ArrayList<>(blockers)) {
                        game.getCombat().removeBlocker(blockerId, game);
                    }
                    blockers.clear();
                    if (!game.isSimulation()) {
                        game.informPlayers(new StringBuilder(attacker.getLogName())
                                .append(" can't be blocked by more than ").append(attacker.getMaxBlockedBy())
                                .append(attacker.getMaxBlockedBy() == 1 ? " creature." : " creatures.")
                                .append(" Blockers discarded.").toString());
                    }

                    blockWasLegal = false;
                }
            }
        }
        return blockWasLegal;
    }

    /**
     * There are effects that let creatures assigns combat damage equal to its
     * toughness rather than its power. So this method takes this into account
     * to get the value of damage a creature will assign
     *
     * @param permanent
     * @param game
     * @return
     */
    private int getDamageValueFromPermanent(Permanent permanent, Game game) {
        if (game.getCombat().useToughnessForDamage(permanent, game)) {
            return permanent.getToughness().getValue();
        } else {
            return permanent.getPower().getValue();
        }
    }

    public void setBlocked(boolean blocked, Game game) {
        this.blocked = blocked;
        for (UUID attackerId : attackers) {
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker != null) {
                for (UUID bandedId : attacker.getBandedCards()) {
                    if (!bandedId.equals(attackerId)) {
                        CombatGroup bandedGroup = game.getCombat().findGroup(bandedId);
                        if (bandedGroup != null) {
                            bandedGroup.blocked = blocked;
                        }
                    }
                }
            }
        }
    }

    public boolean getBlocked() {
        return blocked;
    }

    @Override
    public CombatGroup copy() {
        return new CombatGroup(this);
    }

    public boolean changeDefenderPostDeclaration(UUID newDefenderId, Game game) {
        if (defenderId.equals(newDefenderId)) {
            return false;
        }
        for (UUID attackerId : attackers) { // changing defender will remove a banded attacker from its current band
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker == null) {
                continue;
            }
            if (attacker.getBandedCards() != null) {
                for (UUID bandedId : attacker.getBandedCards()) {
                    Permanent banded = game.getPermanent(bandedId);
                    if (banded != null) {
                        banded.removeBandedCard(attackerId);
                    }
                }
            }
            attacker.clearBandedCards();
        }
        Permanent permanent = game.getPermanent(newDefenderId);
        if (permanent != null) {
            defenderId = newDefenderId;
            defendingPlayerId = permanent.isBattle(game) ? permanent.getProtectorId() : permanent.getControllerId();
            defenderIsPermanent = true;
            return true;
        }
        Player defender = game.getPlayer(newDefenderId);
        if (defender == null) {
            return false;
        }
        defenderId = newDefenderId;
        defendingPlayerId = newDefenderId;
        defenderIsPermanent = false;
        return true;
    }

    /**
     * Decides damage distribution for attacking banding creatures.
     *
     * @param game
     */
    public boolean attackerAssignsCombatDamage(Game game) {
        for (UUID attackerId : attackers) {
            Permanent attacker = game.getPermanent(attackerId);
            if (attacker != null) {
                if (hasBanding(attacker)) { // 702.21k - only one attacker with banding necessary
                    return true;
                }
            }
        }
        // 702.21k - both a [quality] creature with “bands with other [quality]” and another [quality] creature (...)
        return appliesBandsWithOther(attackers, game);
    }

    /**
     * Decides damage distribution for blocking creatures with banding or if
     * defending player controls the Defensive Formation enchantment.
     *
     * @param game
     */
    public boolean defenderAssignsCombatDamage(Game game) {
        for (UUID blockerId : blockers) {
            Permanent blocker = game.getPermanent(blockerId);
            if (blocker != null) {
                if (hasBanding(blocker)) { // 702.21j - only one blocker with banding necessary
                    return true;
                }
            }
        }
        if (appliesBandsWithOther(blockers, game)) { // 702.21j - both a [quality] creature with “bands with other [quality]” and another [quality] creature (...)
            return true;
        }
        for (Permanent defensiveFormation : game.getBattlefield().getAllActivePermanents(defendingPlayerId)) {
            if (defensiveFormation.getAbilities().containsKey(ControllerAssignCombatDamageToBlockersAbility.getInstance().getId())) {
                return true;
            }
        }
        return false;
    }

    public boolean assignsDefendingPlayerAndOrDefendingCreaturesDividedDamage(Permanent creature, UUID playerId, boolean first, Game game, boolean isAttacking) {
        // for handling Butcher Orgg
        if (creature.getAbilities().containsKey(ControllerDivideCombatDamageAbility.getInstance().getId())) {
            Player player = game.getPlayer(defenderAssignsCombatDamage(game) ? defendingPlayerId : (!isAttacking && attackerAssignsCombatDamage(game) ? game.getCombat().getAttackingPlayerId() : playerId));
            // 10/4/2004 	If it is blocked but then all of its blockers are removed before combat damage is assigned, then it won't be able to deal combat damage and you won't be able to use its ability.
            // (same principle should apply if it's blocking and its blocked attacker is removed from combat)
            if (!((blocked && blockers.isEmpty() && isAttacking) || (attackers.isEmpty() && !isAttacking)) && dealsDamageThisStep(creature, first, game)) {
                if (player.chooseUse(Outcome.Damage, "Have " + creature.getLogName() + " assign its combat damage divided among defending player and/or any number of defending creatures?", null, game)) {
                    defendingPlayerAndOrDefendingCreaturesDividedDamage(creature, player, first, game, isAttacking);
                    return true;
                }
            }
        }
        return false;
    }

    private static int getLethalDamage(Permanent blocker, Permanent attacker, Game game) {
        return blocker.getLethalDamage(attacker.getId(), game);
    }

    @Override
    public String toString() {
        return String.format("%d attackers, %d blockers",
                this.getAttackers().size(),
                this.getBlockers().size()
        );
    }
}
