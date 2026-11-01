package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.18 (Everything Train R9 — the combined chain card): a
 * finished chain's answer, composed from its steps' own
 * AnswerCards. Each step's card is built by the one card
 * builder from that step's trail — live and rebuilt cards are
 * one builder, as everywhere — and this class only composes:
 * the chain's name, one section per completed step (its
 * number, its goal as run, its card entire), and a closing
 * line naming the final step's outcome.
 *
 * The combined card exists only over steps that answered:
 * the chain-run state lists done steps' trails only, so a
 * chain that paused mid-way has no combined card until its
 * last step completes.
 *
 * Pure: no Android, no I/O — the Activity hands it the step
 * cards it rebuilt from the trails directory.
 */
public final class ChainCard {

    public String chainName = "";

    /** One completed step's section: its 1-based number and
     *  its own card. */
    public static final class Section {
        public final int number;
        public final AnswerCard card;

        public Section(int number, AnswerCard card) {
            this.number = number;
            this.card = card;
        }
    }

    public final List<Section> sections = new ArrayList<Section>();

    private ChainCard() { }

    /** Compose from per-step cards (nulls skipped — a step
     *  whose trail no longer yields a card is absent, not
     *  fatal). Null when no step yields a card. */
    public static ChainCard fromCards(String chainName,
                                      List<AnswerCard> cards) {
        if (cards == null) return null;
        ChainCard cc = new ChainCard();
        cc.chainName = (chainName == null) ? "" : chainName;
        for (int i = 0; i < cards.size(); i++) {
            AnswerCard card = cards.get(i);
            if (card == null) continue;
            cc.sections.add(new Section(i + 1, card));
        }
        return cc.sections.isEmpty() ? null : cc;
    }

    /** The header's name: the chain's own, or the plain word
     *  for an ad-hoc follow-up (an unnamed chain). */
    public String displayName() {
        return (chainName != null && chainName.length() > 0)
                ? chainName : "Follow-up chain";
    }

    /** The final step's outcome line ("" when the chain has
     *  no completed step to name). */
    public String finalOutcomeLine() {
        if (sections.isEmpty()) return "";
        return sections.get(sections.size() - 1).card.outcomeLine;
    }

    /** The combined card as shown: a header, each step's own
     *  card text under its step heading, and the closing line. */
    public String cardText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Chain: ").append(displayName());
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            sb.append("\n\n— Step ").append(s.number)
                    .append(" of ").append(sections.size())
                    .append(" —\n");
            sb.append(s.card.cardText());
        }
        sb.append("\n\nChain outcome: ").append(finalOutcomeLine());
        return sb.toString();
    }

    /** The combined card as plain text for the share sheet:
     *  the chain's name over the per-step card texts (the one
     *  builder's own words per step), through the card's
     *  existing share path. */
    public String shareText(String dateLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ghost Hands");
        if (dateLabel != null && dateLabel.length() > 0) {
            sb.append(" — ").append(dateLabel);
        }
        sb.append("\nChain: ").append(displayName());
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            sb.append("\n\n— Step ").append(s.number)
                    .append(" of ").append(sections.size())
                    .append(" —\n");
            sb.append(s.card.cardText());
        }
        sb.append("\n\nChain outcome: ").append(finalOutcomeLine());
        return sb.toString();
    }
}
