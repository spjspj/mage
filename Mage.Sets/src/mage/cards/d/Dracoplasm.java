package mage.cards.d;

import java.util.UUID;
import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.costs.mana.ColoredManaCost;
import mage.abilities.effects.ContinuousEffect;
import mage.abilities.effects.ReplacementEffectImpl;
import mage.abilities.effects.common.continuous.BoostSourceEffect;
import mage.abilities.effects.common.continuous.SetBasePowerToughnessSourceEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.*;
import mage.filter.common.FilterControlledCreaturePermanent;
import mage.filter.predicate.mageobject.AnotherPredicate;
import mage.game.Game;
import mage.game.events.EntersTheBattlefieldEvent;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.Target;
import mage.target.common.TargetSacrifice;
import mage.util.CardUtil;

/**
 *
 * @author LevelX2
 */
public final class Dracoplasm extends CardImpl {

    public Dracoplasm(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId,setInfo,new CardType[]{CardType.CREATURE},"{U}{R}");
        this.subtype.add(SubType.SHAPESHIFTER);
        this.power = new MageInt(0);
        this.toughness = new MageInt(0);

        // Flying
        this.addAbility(FlyingAbility.getInstance());

        // As Dracoplasm enters the battlefield, sacrifice any number of creatures. Dracoplasm's power becomes the total power of those creatures and its toughness becomes their total toughness.
        this.addAbility(new SimpleStaticAbility(Zone.ALL, new DracoplasmEffect()));

        // {R}: Dracoplasm gets +1/+0 until end of turn.
        this.addAbility(new SimpleActivatedAbility(new BoostSourceEffect(1, 0, Duration.EndOfTurn), new ColoredManaCost(ColoredManaSymbol.R)));
    }

    private Dracoplasm(final Dracoplasm card) {
        super(card);
    }

    @Override
    public Dracoplasm copy() {
        return new Dracoplasm(this);
    }
}

class DracoplasmEffect extends ReplacementEffectImpl {

    private static final FilterControlledCreaturePermanent filter = new FilterControlledCreaturePermanent();

    static {
        filter.add(AnotherPredicate.instance);
    }

    DracoplasmEffect() {
        super(Duration.EndOfGame, Outcome.BoostCreature);
        this.staticText = "As {this} enters, sacrifice any number of creatures. {this}'s power becomes the total power of those creatures and its toughness becomes their total toughness";
    }

    private DracoplasmEffect(final DracoplasmEffect effect) {
        super(effect);
    }

    @Override
    public DracoplasmEffect copy() {
        return new DracoplasmEffect(this);
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.ENTERS_THE_BATTLEFIELD;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        return event.getTargetId().equals(source.getSourceId());
    }

    @Override
    public boolean replaceEvent(GameEvent event, Ability source, Game game) {
        Permanent creature = ((EntersTheBattlefieldEvent) event).getTarget();
        Player controller = game.getPlayer(source.getControllerId());
        if (creature == null || controller == null) {
            return false;
        }

        Target target = new TargetSacrifice(0, Integer.MAX_VALUE, filter);
        if (!target.canChoose(source.getControllerId(), source, game)) {
            return false;
        }

        controller.choose(Outcome.Detriment, target, source, game);
        if (target.getTargets().isEmpty()) {
            return false;
        }

        int power = 0;
        int toughness = 0;
        for (UUID targetId : target.getTargets()) {
            Permanent targetCreature = game.getPermanent(targetId);
            if (targetCreature != null && targetCreature.sacrifice(source, game)) {
                power = CardUtil.overflowInc(power, targetCreature.getPower().getValue());
                toughness = CardUtil.overflowInc(toughness, targetCreature.getToughness().getValue());
            }
        }
        ContinuousEffect effect = new SetBasePowerToughnessSourceEffect(power, toughness, Duration.WhileOnBattlefield);
        game.addEffect(effect, source);
        this.discard(); // prevent multiple replacements e.g. on blink
        return false;
    }
}
