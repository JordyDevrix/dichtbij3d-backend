package nl.dichtbij3d.backend.service

import nl.dichtbij3d.backend.dto.CostEstimateRequest
import nl.dichtbij3d.backend.dto.CostEstimateResponse
import nl.dichtbij3d.backend.dto.CostLine
import nl.dichtbij3d.backend.dto.PrinterModelDto
import nl.dichtbij3d.backend.repo.PrinterModelRepository
import nl.dichtbij3d.backend.web.ApiException
import org.springframework.stereotype.Service
import kotlin.math.roundToInt

/**
 * Smart 3D print cost calculator.
 *
 * Takes the selected printer (for its wattage and depreciation), the print duration,
 * the filament price and the product weight and produces a full cost breakdown.
 */
@Service
class CalculatorService(
    private val printerRepository: PrinterModelRepository,
    private val mapper: DtoMapper,
) {

    fun printers(): List<PrinterModelDto> =
        printerRepository.findAllByOrderByBrandAscNameAsc().map(mapper::printer)

    fun estimate(request: CostEstimateRequest): CostEstimateResponse {
        val printer = request.printerModelId?.let {
            printerRepository.findById(it).orElseThrow { ApiException.notFound("Printer model") }
        }
        val watts = request.wattsOverride ?: printer?.watts
        ?: throw ApiException.badRequest("Select a printer or enter the wattage manually")

        val totalHours = request.printHours + (request.printMinutes / 60.0)
        if (totalHours <= 0) throw ApiException.badRequest("Print duration must be greater than zero")

        // Printers rarely draw their peak wattage continuously; 0.6 is a realistic
        // average duty cycle for FDM machines (heat bed + hotend cycling).
        val dutyCycle = if (printer?.technology == "RESIN") 0.85 else 0.6
        val energyKwh = watts * dutyCycle * totalHours / 1000.0
        val energyCents = energyKwh * request.electricityPricePerKwhCents

        val filamentGrams = request.productWeightGrams + request.wasteGrams
        val filamentCents = filamentGrams / 1000.0 * request.filamentPricePerKgCents

        val depreciationCents = if (request.includeMachineDepreciation && printer != null &&
            printer.expectedLifetimeHours > 0
        ) {
            printer.purchasePriceCents.toDouble() / printer.expectedLifetimeHours * totalHours
        } else 0.0

        // Nozzles, belts, build plates, lubrication: roughly 4 cents per print hour.
        val maintenanceCents = if (printer != null) 4.0 * totalHours else 0.0

        val labourCents = request.labourMinutes / 60.0 * request.labourRatePerHourCents

        val baseCents = energyCents + filamentCents + depreciationCents + maintenanceCents + labourCents
        val failureCents = baseCents * (request.failureRatePercent / 100.0)

        val subtotal = baseCents + failureCents
        val margin = subtotal * (request.marginPercent / 100.0)
        val beforeVat = subtotal + margin
        val vat = if (request.includeVat) beforeVat * (request.vatPercent / 100.0) else 0.0
        val total = beforeVat + vat

        val lines = listOf(
            CostLine("filament", filamentCents.roundToInt()),
            CostLine("energy", energyCents.roundToInt()),
            CostLine("depreciation", depreciationCents.roundToInt()),
            CostLine("maintenance", maintenanceCents.roundToInt()),
            CostLine("labour", labourCents.roundToInt()),
            CostLine("failureRisk", failureCents.roundToInt()),
        ).filter { it.amountCents > 0 }

        return CostEstimateResponse(
            printerLabel = printer?.let { "${it.brand} ${it.name}" },
            watts = watts,
            totalHours = round2(totalHours),
            energyKwh = round2(energyKwh),
            filamentGrams = round2(filamentGrams),
            lines = lines,
            subtotalCents = subtotal.roundToInt(),
            marginCents = margin.roundToInt(),
            vatCents = vat.roundToInt(),
            totalCents = total.roundToInt(),
            suggestedPriceCents = roundToNice(total),
        )
    }

    /** Rounds up to the nearest 50 cents, the way people actually price things. */
    private fun roundToNice(cents: Double): Int {
        if (cents <= 0) return 0
        return (Math.ceil(cents / 50.0) * 50).toInt()
    }

    private fun round2(value: Double) = Math.round(value * 100.0) / 100.0
}
