package ir.peygir.app

import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    @Test fun presentationNeverOffersFinalApprovalToAnAssistantOrDeputy() {
        val task=Resolution(id=1,status=Status.REVIEW,assignee="a")
        assertNull(Presentation.taskAction("assistant",task));assertNull(Presentation.taskAction("deputy",task))
        assertEquals(Status.DONE,Presentation.taskAction("manager",task)!!.second)
        assertEquals(Status.REVIEW,Presentation.taskAction("manager",task.copy(status=Status.WAITING))!!.second)
        assertNull(Presentation.taskAction("manager",task.copy(status=Status.DONE)))
    }
    @Test fun defaultListsHideCompletedWorkButItRemainsAvailableThroughFilters() {
        val done=Resolution(status=Status.DONE,due=100)
        assertFalse(taskMatchesFilter(done,100,"open"));assertTrue(taskMatchesFilter(done,100,"done"));assertTrue(taskMatchesFilter(done,100,"all"))
        val tasks=listOf(done.copy(id=1),Resolution(id=2,due=99),Resolution(id=3,due=103,status=Status.REVIEW))
        assertEquals(listOf(3L,2L,1L),tasks.sortedWith(Presentation.order(100,"manager")).map{it.id})
    }
}
