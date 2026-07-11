"use strict";

var workbenchContent = document.getElementById("workbenchContent");
var workbenchLoading = document.getElementById("workbenchLoading");
var workbenchAssistantPage = document.getElementById("assistantPage");
var workbenchMonitoringPage = document.getElementById("monitoringPage");
var workbenchNavItems = document.querySelectorAll("[data-workbench-route]");
var assistantToggleButton = document.getElementById("assistantToggleButton");
var assistantDrawer = document.getElementById("assistantDrawer");
var assistantCloseButton = document.getElementById("assistantCloseButton");
var assistantWorkspace = document.getElementById("assistantWorkspace");
var assistantHomeMount = document.getElementById("assistantHomeMount");
var assistantDrawerMount = document.getElementById("assistantDrawerMount");

var WORKBENCH_ROUTES = {
  assistant: {requiresAdmin: false},
  monitoring: {requiresAdmin: true},
};

function currentWorkbenchRoute() {
  var route = window.location.hash.replace(/^#\/?/, "");
  return WORKBENCH_ROUTES[route] ? route : "assistant";
}

function mountWorkbenchPages() {
  if (workbenchMonitoringPage.parentElement !== workbenchContent) {
    workbenchContent.appendChild(workbenchMonitoringPage);
  }
}

function updateWorkbenchNavigation(route) {
  workbenchNavItems.forEach(function(item) {
    var active = item.dataset.workbenchRoute === route;
    item.classList.toggle("active", active);
    if (active) {
      item.setAttribute("aria-current", "page");
    } else {
      item.removeAttribute("aria-current");
    }
  });
}

function applyWorkbenchRoute() {
  var route = currentWorkbenchRoute();
  var definition = WORKBENCH_ROUTES[route];
  mountWorkbenchPages();
  document.querySelectorAll(".workbench-page").forEach(function(page) {
    page.hidden = page.dataset.route !== route;
  });
  updateWorkbenchNavigation(route);
  if (workbenchLoading) workbenchLoading.hidden = true;

  if (route === "assistant") {
    showAssistantPage();
  } else {
    prepareBusinessPage();
  }

  if (!currentUser) return;
  if (definition.requiresAdmin && !adminToken) {
    requireAdminThenOpen(route);
    return;
  }
  if (route === "monitoring" && window.activateMonitoringPage) {
    window.activateMonitoringPage();
  }
}

function navigateWorkbench(route) {
  var target = "#/" + (WORKBENCH_ROUTES[route] ? route : "assistant");
  if (window.location.hash === target) {
    applyWorkbenchRoute();
    return;
  }
  window.location.hash = target;
}

function initializeWorkbench() {
  mountWorkbenchPages();
  if (!currentUser) return;
  navigateWorkbench(currentWorkbenchRoute());
}

function mountAssistantWorkspace(target) {
  var mount = target === "drawer" ? assistantDrawerMount : assistantHomeMount;
  if (assistantWorkspace.parentElement !== mount) {
    mount.appendChild(assistantWorkspace);
  }
  assistantWorkspace.classList.toggle("compact", target === "drawer");
}

function ensureAssistantWelcome() {
  if (!messages.children.length) addWelcomeMessage();
}

function showAssistantPage() {
  assistantDrawer.hidden = true;
  assistantToggleButton.hidden = true;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  mountAssistantWorkspace("home");
  ensureAssistantWelcome();
}

function prepareBusinessPage() {
  assistantDrawer.hidden = true;
  assistantToggleButton.hidden = false;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  mountAssistantWorkspace("drawer");
}

function openAssistantDrawer() {
  if (currentWorkbenchRoute() === "assistant") return;
  mountAssistantWorkspace("drawer");
  assistantDrawer.hidden = false;
  assistantToggleButton.setAttribute("aria-expanded", "true");
  messages.scrollTop = messages.scrollHeight;
  queryInput.focus();
}

function closeAssistantDrawer() {
  assistantDrawer.hidden = true;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  assistantToggleButton.focus();
}

function toggleAssistantDrawer() {
  if (assistantDrawer.hidden) {
    openAssistantDrawer();
  } else {
    closeAssistantDrawer();
  }
}

window.initializeWorkbench = initializeWorkbench;
window.navigateWorkbench = navigateWorkbench;
window.openMonitoringWorkbench = function() {
  return navigateWorkbench("monitoring");
};

workbenchNavItems.forEach(function(item) {
  item.addEventListener("click", function() {
    navigateWorkbench(item.dataset.workbenchRoute);
  });
});

assistantToggleButton.addEventListener("click", toggleAssistantDrawer);
assistantCloseButton.addEventListener("click", closeAssistantDrawer);
document.addEventListener("keydown", function(event) {
  if (event.key === "Escape" && !assistantDrawer.hidden) {
    closeAssistantDrawer();
  }
});

window.addEventListener("hashchange", applyWorkbenchRoute);
initializeWorkbench();
