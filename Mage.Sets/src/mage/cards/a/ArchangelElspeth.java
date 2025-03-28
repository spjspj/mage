package mage.cards.a;

import mage.abilities.Ability;
import mage.abilities.LoyaltyAbility;
import mage.abilities.effects.common.CreateTokenEffect;
import mage.abilities.effects.common.ReturnFromYourGraveyardToBattlefieldAllEffect;
import mage.abilities.effects.common.continuous.AddCardSubTypeTargetEffect;
import mage.abilities.effects.common.continuous.GainAbilityTargetEffect;
import mage.abilities.effects.common.counter.AddCountersTargetEffect;
import mage.abilities.keyword.FlyingAbility;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.*;
import mage.counters.CounterType;
import mage.filter.FilterCard;
import mage.filter.common.FilterNonlandCard;
import mage.filter.predicate.mageobject.ManaValuePredicate;
import mage.filter.predicate.mageobject.PermanentPredicate;
import mage.game.permanent.token.SoldierLifelinkToken;
import mage.target.common.TargetCreaturePermanent;

import java.util.UUID;

/**
 * @author TheElk801
 */
public final class ArchangelElspeth extends CardImpl {

    private static final FilterCard filter = new FilterNonlandCard("nonland permanent cards with mana value 3 or less");

    static {
        filter.add(PermanentPredicate.instance);
        filter.add(new ManaValuePredicate(ComparisonType.FEWER_THAN, 4));
    }

    public ArchangelElspeth(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.PLANESWALKER}, "{2}{W}{W}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.ELSPETH);
        this.setStartingLoyalty(4);

        // +1: Create a 1/1 white Soldier creature token with lifelink.
        this.addAbility(new LoyaltyAbility(new CreateTokenEffect(new SoldierLifelinkToken()), 1));

        // -2: Put two +1/+1 counters on target creature. It becomes an Angel in addition to its other types and gains flying.
        Ability ability = new LoyaltyAbility(new AddCountersTargetEffect(CounterType.P1P1.createInstance(2)), -2);
        ability.addEffect(new AddCardSubTypeTargetEffect(SubType.ANGEL, Duration.Custom)
                .setText("It becomes an Angel in addition to its other types"));
        ability.addEffect(new GainAbilityTargetEffect(FlyingAbility.getInstance(), Duration.Custom).setText("and gains flying"));
        ability.addTarget(new TargetCreaturePermanent());
        this.addAbility(ability);

        // -6: Return all nonland permanent cards with mana value 3 or less from your graveyard to the battlefield.
        this.addAbility(new LoyaltyAbility(new ReturnFromYourGraveyardToBattlefieldAllEffect(filter), -6));
    }

    private ArchangelElspeth(final ArchangelElspeth card) {
        super(card);
    }

    @Override
    public ArchangelElspeth copy() {
        return new ArchangelElspeth(this);
    }
}
