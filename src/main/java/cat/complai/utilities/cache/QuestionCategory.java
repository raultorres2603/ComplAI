package cat.complai.utilities.cache;

/**
 * Categories for question classification to enable smart caching.
 * 
 * Each category has typical keywords that help classify incoming questions.
 * This enables cache hits across different phrasings of the same type of question.
 * 
 * Privacy note: Category is deterministic (no user-specific data) and safe for cache keys.
 */
public enum QuestionCategory {
    /**
     * Parking permits, parking zones, parking fines, parking reservations.
     * Keywords: parking, permit, zone, fine, reservation, vehicle, car, spot
     */
    PARKING,
    
    /**
     * Tax payments, tax rates, tax deductions, tax filing.
     * Keywords: tax, rate, filing, payment, deduction, municipal, irs, contribution
     */
    TAX,
    
    /**
     * Garbage collection, waste management, recycling programs, collection schedules.
     * Keywords: garbage, waste, recycling, collection, schedule, trash, bin
     */
    GARBAGE,
    
    /**
     * Library services, book reservations, library hours, membership.
     * Keywords: library, book, reservation, hours, membership, loan, fiction
     */
    LIBRARY,
    
    /**
     * Complaints, grievances, claims, legal disputes.
     * Keywords: complaint, grievance, claim, dispute, legal, compensation
     */
    COMPLAINT,
    
    /**
     * Administrative services, licenses, permits, certifications, bureaucracy.
     * Keywords: license, permit, certification, certificate, registration, form, application
     */
    ADMINISTRATION,
    
    /**
     * Any other question that doesn't fit above categories.
     */
    OTHER
}
