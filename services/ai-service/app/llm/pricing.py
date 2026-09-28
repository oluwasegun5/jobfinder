import logging
from decimal import Decimal

from app.config import ModelPricing

logger = logging.getLogger(__name__)

_MTOK = Decimal(1_000_000)
_QUANTUM = Decimal("0.000001")


def estimate_cost_usd(
    pricing: dict[str, ModelPricing], model: str, input_tokens: int, output_tokens: int
) -> Decimal:
    """Cost of one call in USD. Unknown models cost 0 (logged) so pricing gaps never block calls."""
    price = pricing.get(model)
    if price is None:
        logger.warning("No pricing configured for model %s; recording cost as 0", model)
        return Decimal(0)
    cost = (
        Decimal(input_tokens) * price.input_per_mtok
        + Decimal(output_tokens) * price.output_per_mtok
    ) / _MTOK
    return cost.quantize(_QUANTUM)
